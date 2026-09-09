package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.Timeframe;
import money.hejje.instruments.Instrument;
import money.hejje.market.Candle;
import money.hejje.market.ContinuousSeries;
import org.junit.jupiter.api.Test;

class ContinuousFuturesBuilderTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static Instrument future(String underlying, LocalDate expiry) {
        return new Instrument(UUID.randomUUID(), underlying, underlying + " FUT", Exchange.NFO, InstrumentType.FUT, underlying, expiry, null, null, 75,
                new BigDecimal("0.05"), null, true, Instant.EPOCH);
    }

    static Candle daily(UUID id, LocalDate day, String close) {
        return new Candle(id, Timeframe.D1, day.atStartOfDay(IST).toInstant(), new BigDecimal(close), new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), 100, 0, false);
    }

    @Test
    void rollsOnExpiryDayWithoutAdjustment() {
        Instrument sep = future("NIFTY", LocalDate.of(2026, 9, 29));
        Instrument oct = future("NIFTY", LocalDate.of(2026, 10, 27));
        Map<Instrument, List<Candle>> data = new LinkedHashMap<>();
        // both contracts trade through the whole window; prices differ (contango) to prove there is no back-adjustment
        data.put(oct, List.of(daily(oct.id(), LocalDate.of(2026, 9, 25), "200"), daily(oct.id(), LocalDate.of(2026, 9, 28), "201"),
                daily(oct.id(), LocalDate.of(2026, 9, 29), "202"), daily(oct.id(), LocalDate.of(2026, 9, 30), "203")));
        data.put(sep, List.of(daily(sep.id(), LocalDate.of(2026, 9, 25), "100"), daily(sep.id(), LocalDate.of(2026, 9, 28), "101"),
                daily(sep.id(), LocalDate.of(2026, 9, 29), "102")));
        UUID seriesId = ContinuousSeries.idFor("NFO:NIFTY:FUT:CONT");

        ContinuousFuturesBuilder.Stitched stitched = ContinuousFuturesBuilder.stitch(data, seriesId, Timeframe.D1, IST);

        assertThat(stitched.candles()).extracting(c -> c.close().toPlainString()).containsExactly("100", "101", "202", "203");
        assertThat(stitched.candles()).allMatch(c -> c.instrumentId().equals(seriesId));
        assertThat(stitched.segments()).hasSize(2);
        ContinuousSeries.Segment first = stitched.segments().get(0);
        assertThat(first.instrumentId()).isEqualTo(sep.id());
        assertThat(first.from()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(first.to()).isEqualTo(LocalDate.of(2026, 9, 28)); // the expiring contract is used up to the day before expiry
        assertThat(first.candles()).isEqualTo(2);
        ContinuousSeries.Segment second = stitched.segments().get(1);
        assertThat(second.instrumentId()).isEqualTo(oct.id());
        assertThat(second.from()).isEqualTo(LocalDate.of(2026, 9, 29)); // roll on the expiry date
        assertThat(second.candles()).isEqualTo(2);
        // stable id
        assertThat(ContinuousSeries.idFor("NFO:NIFTY:FUT:CONT")).isEqualTo(seriesId);
        assertThat(ContinuousSeries.isContinuousSymbol("nfo:nifty:fut:cont")).isTrue();
    }

    @Test
    void sessionsAfterTheLastExpiryAreDropped() {
        Instrument sep = future("NIFTY", LocalDate.of(2026, 9, 29));
        Map<Instrument, List<Candle>> data = Map.of(sep, List.of(daily(sep.id(), LocalDate.of(2026, 9, 28), "1"), daily(sep.id(), LocalDate.of(2026, 9, 29), "2")));
        ContinuousFuturesBuilder.Stitched stitched = ContinuousFuturesBuilder.stitch(data, UUID.randomUUID(), Timeframe.D1, IST);
        assertThat(stitched.candles()).hasSize(1);
    }
}
