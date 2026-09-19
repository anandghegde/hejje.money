package money.hejje.market.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.MarketTick;
import money.hejje.common.event.TickBus;
import money.hejje.market.MarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Replays a recorded day's ticks (from {@link TickRecorder}) into the pipeline at a configurable speed. Dev only. Each
 * replayed tick goes to the same {@link MarketPipeline} entry point the live streamer uses, so candles are identical.
 */
@Component
@Profile("dev")
public class ReplayMarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(ReplayMarketDataSource.class);

    private final Path root;
    private final MarketPipeline pipeline;
    private final java.time.ZoneId zone;

    ReplayMarketDataSource(HejjeProperties properties, MarketProperties market, MarketPipeline pipeline) {
        this.root = properties.dataDir().resolve("ticks");
        this.pipeline = pipeline;
        this.zone = properties.timezone();
    }

    /** Replays a recorded day into the pipeline. {@code speed} > 1 is faster than real time; 0 means as fast as possible. */
    public void replay(LocalDate day, double speed) {
        Path file = root.resolve(day.toString()).resolve("ticks.parquet");
        List<MarketTick> ticks = readFile(file);
        log.info("Replaying {} ticks from {} at {}x", ticks.size(), day, speed);
        java.time.Instant previous = null;
        for (MarketTick tick : ticks) {
            if (speed > 0 && previous != null) {
                long gapMs = java.time.Duration.between(previous, tick.ts()).toMillis();
                long sleep = (long) (gapMs / speed);
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            pipeline.onTick(tick);
            previous = tick.ts();
        }
        // an empty day closes as of its session close (never the wall clock: replays must not depend on when they run)
        pipeline.closeCandlesAsOf(ticks.isEmpty() ? day.atTime(money.hejje.common.time.HejjeClock.SESSION_CLOSE).atZone(zone).toInstant().plusSeconds(120)
                : ticks.get(ticks.size() - 1).ts().plusSeconds(120));
    }

    static List<MarketTick> readFile(Path file) {
        List<MarketTick> out = new ArrayList<>();
        String sql = "SELECT instrument_id, ts, last_price, bid, ask, volume, oi, mode FROM read_parquet('" + file.toAbsolutePath() + "') ORDER BY ts";
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:"); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                BigDecimal bid = rs.getObject("bid") == null ? null : bd(rs.getDouble("bid"));
                BigDecimal ask = rs.getObject("ask") == null ? null : bd(rs.getDouble("ask"));
                out.add(new MarketTick(UUID.fromString(rs.getString("instrument_id")), rs.getObject("ts", java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC),
                        bd(rs.getDouble("last_price")), bid, ask, rs.getLong("volume"), rs.getLong("oi"),
                        MarketTick.Mode.valueOf(rs.getString("mode"))));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read ticks from " + file, e);
        }
        return out;
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
