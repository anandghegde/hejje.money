package money.hejje.market.internal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.common.config.HejjeProperties;
import money.hejje.market.Candle;
import money.hejje.market.CandleCoverage;
import money.hejje.market.HistoricalCandleStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parquet-backed candle store queried through DuckDB (JDBC, in-memory connection per operation). Files live at
 * {@code <data-dir>/candles/{timeframe}/{instrumentId}/{yyyy}.parquet}. Writes merge with any existing rows for the
 * affected years (read existing + new, dedupe by openTime, COPY back). Reads glob the year files with a date filter.
 */
@Component
public class DuckDbHistoricalCandleStore implements HistoricalCandleStore {

    private static final Logger log = LoggerFactory.getLogger(DuckDbHistoricalCandleStore.class);

    private final Path root;
    private volatile long writes;

    DuckDbHistoricalCandleStore(HejjeProperties properties) {
        this.root = properties.dataDir().resolve("candles");
    }

    static {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("DuckDB JDBC driver not on the classpath", e);
        }
    }

    private Path file(UUID instrumentId, Timeframe timeframe, int year) {
        return root.resolve(timeframe.name()).resolve(instrumentId.toString()).resolve(year + ".parquet");
    }

    private static int yearOf(Instant openTime) {
        return openTime.atZone(ZoneOffset.UTC).getYear();
    }

    @Override
    public synchronized void write(UUID instrumentId, Timeframe timeframe, List<Candle> candles) {
        if (candles.isEmpty()) {
            return;
        }
        try {
            candles.stream().map(c -> yearOf(c.openTime())).distinct().forEach(year -> writeYear(instrumentId, timeframe, year,
                    candles.stream().filter(c -> yearOf(c.openTime()) == year).toList()));
        } finally {
            writes++;
        }
    }

    @Override
    public long writes() {
        return writes;
    }

    private void writeYear(UUID instrumentId, Timeframe timeframe, int year, List<Candle> yearCandles) {
        Path target = file(instrumentId, timeframe, year);
        try {
            Files.createDirectories(target.getParent());
            List<Candle> merged = new ArrayList<>();
            if (Files.exists(target)) {
                merged.addAll(read(instrumentId, timeframe, Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"), target));
            }
            java.util.Map<Instant, Candle> byTime = new java.util.TreeMap<>();
            merged.forEach(c -> byTime.put(c.openTime(), c));
            yearCandles.forEach(c -> byTime.put(c.openTime(), c));
            copyToParquet(target, instrumentId, timeframe, new ArrayList<>(byTime.values()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write parquet " + target, e);
        }
    }

    private void copyToParquet(Path target, UUID instrumentId, Timeframe timeframe, List<Candle> candles) throws SQLException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (Connection conn = connect()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TEMP TABLE c (open_time TIMESTAMP, open DOUBLE, high DOUBLE, low DOUBLE, close DOUBLE, volume BIGINT, oi BIGINT, synthetic BOOLEAN)");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO c VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Candle c : candles) {
                    ps.setString(1, iso(c.openTime()));
                    ps.setDouble(2, c.open().doubleValue());
                    ps.setDouble(3, c.high().doubleValue());
                    ps.setDouble(4, c.low().doubleValue());
                    ps.setDouble(5, c.close().doubleValue());
                    ps.setLong(6, c.volume());
                    ps.setLong(7, c.oi());
                    ps.setBoolean(8, c.synthetic());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT * FROM c ORDER BY open_time) TO '" + tmp.toAbsolutePath() + "' (FORMAT PARQUET)");
            }
        }
        try {
            Files.move(tmp.toAbsolutePath(), target.toAbsolutePath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to replace " + target, e);
        }
    }

    @Override
    public List<Candle> read(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        Path dir = root.resolve(timeframe.name()).resolve(instrumentId.toString());
        if (!Files.exists(dir)) {
            return List.of();
        }
        String glob = dir.toAbsolutePath() + "/*.parquet";
        return read(instrumentId, timeframe, from, to, null, glob);
    }

    private List<Candle> read(UUID instrumentId, Timeframe timeframe, Instant from, Instant to, Path singleFile) {
        return read(instrumentId, timeframe, from, to, singleFile, null);
    }

    private List<Candle> read(UUID instrumentId, Timeframe timeframe, Instant from, Instant to, Path singleFile, String glob) {
        String source = singleFile != null ? "'" + singleFile.toAbsolutePath() + "'" : "'" + glob + "'";
        String sql = "SELECT open_time, open, high, low, close, volume, oi, synthetic FROM read_parquet(" + source
                + ") WHERE open_time >= ? AND open_time <= ? ORDER BY open_time";
        List<Candle> out = new ArrayList<>();
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, iso(from));
            ps.setString(2, iso(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Candle(instrumentId, timeframe, rs.getObject("open_time", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            bd(rs.getDouble("open")), bd(rs.getDouble("high")), bd(rs.getDouble("low")), bd(rs.getDouble("close")),
                            rs.getLong("volume"), rs.getLong("oi"), rs.getBoolean("synthetic")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read parquet for " + instrumentId + "/" + timeframe, e);
        }
        return out;
    }

    @Override
    public CandleCoverage coverage(UUID instrumentId, Timeframe timeframe) {
        Path dir = root.resolve(timeframe.name()).resolve(instrumentId.toString());
        if (!Files.exists(dir)) {
            return new CandleCoverage(instrumentId, timeframe, null, null, 0);
        }
        String glob = dir.toAbsolutePath() + "/*.parquet";
        String sql = "SELECT min(open_time) mn, max(open_time) mx, count(*) cnt FROM read_parquet('" + glob + "')";
        try (Connection conn = connect(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next() && rs.getObject("cnt") != null) {
                long count = rs.getLong("cnt");
                java.time.LocalDateTime mn = rs.getObject("mn", java.time.LocalDateTime.class);
                java.time.LocalDateTime mx = rs.getObject("mx", java.time.LocalDateTime.class);
                Instant min = mn == null ? null : mn.toInstant(ZoneOffset.UTC);
                Instant max = mx == null ? null : mx.toInstant(ZoneOffset.UTC);
                return new CandleCoverage(instrumentId, timeframe, min, max, count);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compute coverage for " + instrumentId + "/" + timeframe, e);
        }
        return new CandleCoverage(instrumentId, timeframe, null, null, 0);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    private static String iso(Instant instant) {
        ZonedDateTime z = instant.atZone(ZoneOffset.UTC);
        return String.format("%04d-%02d-%02d %02d:%02d:%02d", z.getYear(), z.getMonthValue(), z.getDayOfMonth(), z.getHour(), z.getMinute(), z.getSecond());
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
