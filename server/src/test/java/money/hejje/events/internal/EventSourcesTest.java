package money.hejje.events.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.events.EventScope;
import money.hejje.events.EventType;
import money.hejje.events.MarketEvent;
import money.hejje.instruments.Instrument;
import org.junit.jupiter.api.Test;

class EventSourcesTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Instrument INFY = new Instrument(UUID.randomUUID(), "INFY", "Infosys", Exchange.NSE, InstrumentType.EQ, null, null, null, null, 1,
            new BigDecimal("0.05"), null, true, Instant.now());

    static Optional<Instrument> resolve(String symbol) {
        return symbol.equals("NSE:INFY") ? Optional.of(INFY) : Optional.empty();
    }

    @Test
    void csvImportParsesRowsReportsErrorsAndKeepsGoing() {
        String csv = """
                type,symbol,title,date,time,end_date,confidence
                RESULTS,NSE:INFY,Q2 results,2026-10-16,16:00,,1.0
                EX_DIVIDEND,NSE:INFY,"Interim dividend, Rs 21",2026-10-28,,,
                BOARD_MEETING,NSE:NOPE,Board meeting,2026-10-20,,,
                RBI_POLICY,,RBI MPC decision,2026-10-07,10:00,,0.95
                WRONG_TYPE,,x,2026-10-01,,,
                """;
        CsvEvents.Parsed parsed = CsvEvents.parse(csv, EventSourcesTest::resolve, IST, Instant.EPOCH, "csv");
        assertThat(parsed.events()).hasSize(3);
        MarketEvent results = parsed.events().get(0);
        assertThat(results.type()).isEqualTo(EventType.RESULTS);
        assertThat(results.scope()).isEqualTo(EventScope.INSTRUMENT);
        assertThat(results.instrumentId()).isEqualTo(INFY.id());
        assertThat(results.allDay()).isFalse();
        assertThat(results.startsAt()).isEqualTo(LocalDate.of(2026, 10, 16).atTime(16, 0).atZone(IST).toInstant());
        assertThat(results.externalKey()).isEqualTo("RESULTS|INSTRUMENT|NSE:INFY|2026-10-16|q2 results");
        MarketEvent dividend = parsed.events().get(1);
        assertThat(dividend.title()).isEqualTo("Interim dividend, Rs 21");
        assertThat(dividend.allDay()).isTrue();
        assertThat(dividend.confidence()).isEqualTo(0.9);
        assertThat(parsed.events().get(2).scope()).isEqualTo(EventScope.MARKET);
        assertThat(parsed.errors()).hasSize(2);
        assertThat(parsed.errors().get(0)).contains("line 4").contains("NSE:NOPE");
        assertThat(parsed.errors().get(1)).contains("line 6");
        assertThat(CsvEvents.parse("nope\n", EventSourcesTest::resolve, IST, Instant.EPOCH, "csv").errors()).containsExactly("header must contain type, title and date");
    }

    @Test
    void curatedYamlParsesAndFiltersByRange() throws Exception {
        String yaml = """
                events:
                  - { type: BUDGET, title: Union Budget, date: 2026-02-01, time: "11:00", confidence: 1.0 }
                  - { type: RBI_POLICY, title: RBI MPC decision, date: 2026-10-07, time: "10:00" }
                  - { type: RESULTS, title: Q2 results, date: 2026-10-16, symbol: NSE:INFY }
                  - { type: NOT_A_TYPE, title: bad, date: 2026-10-16 }
                """;
        List<MarketEvent> events = CuratedYamlSource.parse(new ObjectMapper(new YAMLFactory()).readTree(yaml), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31),
                EventSourcesTest::resolve, IST, Instant.EPOCH, "curated");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo(EventType.RBI_POLICY);
        assertThat(events.get(0).confidence()).isEqualTo(0.9);
        assertThat(events.get(0).raw()).containsEntry("time", "10:00");
        assertThat(events.get(1).scope()).isEqualTo(EventScope.INSTRUMENT);
        assertThat(events.get(1).instrumentId()).isEqualTo(INFY.id());
        assertThat(events.get(1).allDay()).isTrue();
    }

    @Test
    void nseFetcherHelpers() {
        assertThat(NseCorporateActionsFetcher.classifyAction("Interim Dividend - Rs 21 Per Share")).isEqualTo(EventType.EX_DIVIDEND);
        assertThat(NseCorporateActionsFetcher.classifyAction("Bonus 1:1")).isEqualTo(EventType.BONUS);
        assertThat(NseCorporateActionsFetcher.classifyAction("Face Value Split (Sub-Division) - From Rs 10/- Per Share To Rs 1/- Per Share")).isEqualTo(EventType.SPLIT);
        assertThat(NseCorporateActionsFetcher.classifyAction("Buyback of shares")).isEqualTo(EventType.BUYBACK);
        assertThat(NseCorporateActionsFetcher.classifyAction("Annual General Meeting")).isEqualTo(EventType.AGM);
        assertThat(NseCorporateActionsFetcher.classifyAction("Rights 1:4")).isEqualTo(EventType.CORPORATE_ACTION);
        assertThat(NseCorporateActionsFetcher.date("16-Oct-2026")).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(NseCorporateActionsFetcher.date("2026-10-16")).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(NseCorporateActionsFetcher.date("-")).isNull();
        assertThat(CsvEvents.split("a,\"b, c\",\"d \"\"q\"\"\",e")).containsExactly("a", "b, c", "d \"q\"", "e");
    }
}
