package money.hejje.broker.zerodha;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import money.hejje.broker.BrokerInstrument;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;
import org.junit.jupiter.api.Test;

class KiteInstrumentCsvTest {

    @Test
    void parsesFixtureAndSkipsUnknownExchanges() throws Exception {
        List<BrokerInstrument> rows;
        try (var in = getClass().getClassLoader().getResourceAsStream("broker/fake/kite-instruments-fixture.csv")) {
            rows = KiteInstrumentCsv.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertThat(rows).hasSize(32); // 33 data rows minus the CDS row

        BrokerInstrument nifty = rows.stream().filter(r -> r.brokerToken().equals("256265")).findFirst().orElseThrow();
        assertThat(nifty.type()).isEqualTo(InstrumentType.INDEX);
        assertThat(nifty.exchange()).isEqualTo(Exchange.INDEX);
        assertThat(nifty.symbol()).isEqualTo("NIFTY 50");
        assertThat(nifty.exchangeSegment()).isEqualTo("NSE");

        BrokerInstrument reliance = rows.stream().filter(r -> r.brokerToken().equals("738561")).findFirst().orElseThrow();
        assertThat(reliance.type()).isEqualTo(InstrumentType.EQ);
        assertThat(reliance.symbol()).isEqualTo("RELIANCE");
        assertThat(reliance.name()).isEqualTo("RELIANCE INDUSTRIES");
        assertThat(reliance.lotSize()).isEqualTo(1);
        assertThat(reliance.tickSize()).isEqualByComparingTo("0.05");

        BrokerInstrument fut = rows.stream().filter(r -> r.tradingSymbol().equals("NIFTY26SEPFUT")).findFirst().orElseThrow();
        assertThat(fut.type()).isEqualTo(InstrumentType.FUT);
        assertThat(fut.symbol()).isEqualTo("NIFTY");
        assertThat(fut.underlying()).isEqualTo("NIFTY");
        assertThat(fut.expiry()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(fut.lotSize()).isEqualTo(75);
        assertThat(fut.exchange()).isEqualTo(Exchange.NFO);

        BrokerInstrument opt = rows.stream().filter(r -> r.tradingSymbol().equals("NIFTY2691525000PE")).findFirst().orElseThrow();
        assertThat(opt.type()).isEqualTo(InstrumentType.OPT);
        assertThat(opt.optionType()).isEqualTo(OptionType.PE);
        assertThat(opt.strike()).isEqualTo(new BigDecimal("25000.00"));
        assertThat(opt.expiry()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(opt.raw()).containsEntry("segment", "NFO-OPT");
    }

    @Test
    void splitsQuotedCells() {
        assertThat(KiteInstrumentCsv.splitCsv("1,\"A, B\",\"say \"\"hi\"\"\",")).containsExactly("1", "A, B", "say \"hi\"", "");
    }
}
