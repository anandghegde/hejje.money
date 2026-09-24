package money.hejje.broker.zerodha;

import static org.assertj.core.api.Assertions.assertThat;

import com.zerodhatech.models.Depth;
import com.zerodhatech.models.Tick;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.event.MarketTick;
import org.junit.jupiter.api.Test;

/** FULL-mode Kite ticks carry the five-level quantities and day totals (plan M9.4); other modes do not. */
class KiteTickDepthTest {

    static Depth level(double price, int qty) {
        Depth d = new Depth();
        d.setPrice(price);
        d.setQuantity(qty);
        return d;
    }

    static Tick tick(String mode) {
        Tick t = new Tick();
        t.setMode(mode);
        t.setLastTradedPrice(1500.5);
        t.setVolumeTradedToday(120_000);
        t.setTickTimestamp(Date.from(Instant.parse("2026-09-23T04:00:00Z")));
        t.setTotalBuyQuantity(300_000);
        t.setTotalSellQuantity(200_000);
        ArrayList<Depth> buy = new ArrayList<>();
        ArrayList<Depth> sell = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            buy.add(level(1500.4 - i * 0.05, 100 * (i + 1)));  // 100..500 → 1500
            sell.add(level(1500.6 + i * 0.05, 50 * (i + 1)));  // 50..250 → 750
        }
        t.setMarketDepth(Map.of("buy", buy, "sell", sell));
        return t;
    }

    @Test
    void fullModeMapsDepthQuantitiesAndDayTotals() {
        MarketTick m = KiteMapper.tick(tick("full"), UUID.randomUUID(), Instant.EPOCH);
        assertThat(m.mode()).isEqualTo(MarketTick.Mode.FULL);
        assertThat(m.bid()).isEqualByComparingTo("1500.40");
        assertThat(m.ask()).isEqualByComparingTo("1500.60");
        assertThat(m.bidQty5()).isEqualTo(1500L);
        assertThat(m.askQty5()).isEqualTo(750L);
        assertThat(m.totalBuyQty()).isEqualTo(300_000L);
        assertThat(m.totalSellQty()).isEqualTo(200_000L);
        assertThat(m.hasBook()).isTrue();
    }

    @Test
    void quoteAndLtpModesCarryNoBook() {
        assertThat(KiteMapper.tick(tick("quote"), UUID.randomUUID(), Instant.EPOCH).hasBook()).isFalse();
        assertThat(KiteMapper.tick(tick("ltp"), UUID.randomUUID(), Instant.EPOCH).hasBook()).isFalse();
    }
}
