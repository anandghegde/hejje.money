package money.hejje.market.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.event.MarketTick;
import money.hejje.market.MarketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Appends ticks to a daily Parquet file at {@code <data-dir>/ticks/{yyyy-MM-dd}/ticks.parquet} when
 * {@code hejje.market.record=true}. Ticks buffer in memory and flush periodically; {@link ReplayMarketDataSource} reads
 * the same files back. Active only when recording is enabled.
 */
@Component
@ConditionalOnProperty(name = "hejje.market.record", havingValue = "true")
public class TickRecorder {

    private static final Logger log = LoggerFactory.getLogger(TickRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final Path root;
    private final List<MarketTick> buffer = new ArrayList<>();

    TickRecorder(HejjeProperties properties, MarketProperties market) {
        this.root = properties.dataDir().resolve("ticks");
    }

    public synchronized void record(MarketTick tick) {
        buffer.add(tick);
        if (buffer.size() >= 1000) {
            flush();
        }
    }

    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<MarketTick> batch = List.copyOf(buffer);
        buffer.clear();
        LocalDate day = batch.get(0).ts().atZone(IST).toLocalDate();
        Path dir = root.resolve(day.toString());
        Path file = dir.resolve("ticks.parquet");
        try {
            Files.createDirectories(dir);
            List<MarketTick> merged = new ArrayList<>();
            if (Files.exists(file)) {
                merged.addAll(ReplayMarketDataSource.readFile(file));
            }
            merged.addAll(batch);
            writeTicks(file, merged);
        } catch (Exception e) {
            log.warn("Failed to record ticks for {}: {}", day, e.getMessage());
        }
    }

    static void writeTicks(Path file, List<MarketTick> ticks) throws SQLException {
        Path tmp = file.resolveSibling("ticks.tmp.parquet");
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TEMP TABLE t (instrument_id VARCHAR, ts TIMESTAMP, last_price DOUBLE, bid DOUBLE, ask DOUBLE, volume BIGINT, oi BIGINT, mode VARCHAR)");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO t VALUES (?,?,?,?,?,?,?,?)")) {
                for (MarketTick tick : ticks) {
                    ps.setString(1, tick.instrumentId().toString());
                    ps.setString(2, DuckIso.of(tick.ts()));
                    ps.setDouble(3, tick.lastPrice().doubleValue());
                    if (tick.bid() != null) ps.setDouble(4, tick.bid().doubleValue()); else ps.setNull(4, java.sql.Types.DOUBLE);
                    if (tick.ask() != null) ps.setDouble(5, tick.ask().doubleValue()); else ps.setNull(5, java.sql.Types.DOUBLE);
                    ps.setLong(6, tick.volume());
                    ps.setLong(7, tick.oi());
                    ps.setString(8, tick.mode().name());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM t ORDER BY ts) TO '" + tmp.toAbsolutePath() + "' (FORMAT PARQUET)");
            }
        }
        try {
            Files.move(tmp.toAbsolutePath(), file.toAbsolutePath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to replace " + file, e);
        }
    }
}
