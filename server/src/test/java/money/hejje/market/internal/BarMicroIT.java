package money.hejje.market.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import money.hejje.common.event.MarketTick;
import money.hejje.common.event.TickBus;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A recorded day replayed twice through the pipeline writes byte-identical bar_micro rows (plan M9.4). Ticks on 2026-12-04. */
class BarMicroIT extends AbstractIntegrationTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired TickBus bus;
    @Autowired QuoteCache quotes;
    @Autowired MarketCandleStore store;
    @Autowired ObjectProvider<TickRecorder> recorder;
    @Autowired HejjeClock clock;
    @Autowired InstrumentService instruments;
    @Autowired JdbcTemplate jdbc;

    List<MarketTick> day(UUID id) {
        List<MarketTick> out = new ArrayList<>();
        long volume = 10_000;
        for (int i = 0; i < 40; i++) {
            BigDecimal price = new BigDecimal("500.00").add(BigDecimal.valueOf((i * 7 % 11) - 5).multiply(new BigDecimal("0.05")));
            volume += 100 + (i * 37 % 300);
            out.add(new MarketTick(id, LocalDateTime.parse("2026-12-04T10:00:00").plusSeconds(15L * i).atZone(IST).toInstant(), price, null, null, volume, 0,
                    MarketTick.Mode.FULL, 1000L + i * 13 % 500, 900L + i * 29 % 400, 50_000L + i * 100, 40_000L + i * 70));
        }
        return out;
    }

    List<Map<String, Object>> replay(UUID id) {
        jdbc.update("DELETE FROM bar_micro WHERE instrument_id = ?", id);
        MarketPipeline pipeline = new MarketPipeline(bus, quotes, store, recorder, clock);
        List<MarketTick> ticks = day(id);
        ticks.forEach(pipeline::onTick);
        pipeline.closeCandlesAsOf(ticks.get(ticks.size() - 1).ts().plusSeconds(600));
        return jdbc.queryForList("SELECT * FROM bar_micro WHERE instrument_id = ? ORDER BY timeframe, open_time", id);
    }

    @Test
    void replayingADayTwiceGivesIdenticalRows() {
        instruments.sync();
        UUID id = instruments.resolve("NSE:SBIN").map(Instrument::id).orElseThrow();
        List<Map<String, Object>> first = replay(id);
        List<Map<String, Object>> second = replay(id);
        assertThat(first).isNotEmpty();
        assertThat(first).extracting(r -> r.get("timeframe")).contains("M1", "M5");
        assertThat(second).isEqualTo(first);
    }
}
