package money.hejje.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.event.MarketTick;
import org.junit.jupiter.api.Test;

class SessionReplayTest {

    static final UUID ID = UUID.fromString("00000000-0000-7000-8000-0000000007a1");
    static final Instant T = Instant.parse("2026-09-09T03:45:00Z");

    static Candle bar(String o, String h, String l, String c, long v) {
        return new Candle(ID, Timeframe.M1, T, new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), v, 7, false);
    }

    @Test
    void anUpBarVisitsTheLowFirstAndADownBarTheHigh() {
        List<MarketTick> up = SessionReplay.syntheticTicks(bar("100", "103", "99", "102", 1_003), 5_000);
        assertThat(up).extracting(MarketTick::lastPrice).map(BigDecimal::toPlainString).containsExactly("100", "99", "103", "102");
        assertThat(up).extracting(t -> t.ts().getEpochSecond() - T.getEpochSecond()).containsExactly(0L, 20L, 40L, 59L);
        // 1003 shares split 250/250/250/253 as cumulative day volume after 5000
        assertThat(up).extracting(MarketTick::volume).containsExactly(5_250L, 5_500L, 5_750L, 6_003L);
        assertThat(up).allMatch(t -> t.oi() == 7 && t.instrumentId().equals(ID));

        List<MarketTick> down = SessionReplay.syntheticTicks(bar("102", "103", "99", "100", 400), 0);
        assertThat(down).extracting(MarketTick::lastPrice).map(BigDecimal::toPlainString).containsExactly("102", "103", "99", "100");
    }
}
