package money.hejje.market.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import money.hejje.common.Timeframe;
import money.hejje.market.Candle;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Recent candles in Postgres ({@code market_candle}); a nightly job prunes older than the retention window. */
@Repository
public class MarketCandleStore {

    private static final String UPSERT = """
            INSERT INTO market_candle (instrument_id, timeframe, open_time, open, high, low, close, volume, oi, synthetic)
            VALUES (:instrumentId, :timeframe, :openTime, :open, :high, :low, :close, :volume, :oi, :synthetic)
            ON CONFLICT (instrument_id, timeframe, open_time) DO UPDATE SET open = EXCLUDED.open, high = EXCLUDED.high,
                low = EXCLUDED.low, close = EXCLUDED.close, volume = EXCLUDED.volume, oi = EXCLUDED.oi, synthetic = EXCLUDED.synthetic
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    MarketCandleStore(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public void save(Candle candle) {
        named.update(UPSERT, params(candle));
    }

    public void saveAll(List<Candle> candles) {
        if (!candles.isEmpty()) {
            named.batchUpdate(UPSERT, candles.stream().map(this::params).toArray(SqlParameterSource[]::new));
        }
    }

    public List<Candle> read(UUID instrumentId, Timeframe timeframe, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT * FROM market_candle WHERE instrument_id = :id AND timeframe = :tf AND open_time >= :from AND open_time <= :to
                ORDER BY open_time
                """)
                .param("id", instrumentId).param("tf", timeframe.name()).param("from", ts(from)).param("to", ts(to))
                .query(this::map).list();
    }

    public Instant earliest(UUID instrumentId, Timeframe timeframe) {
        return jdbc.sql("SELECT min(open_time) FROM market_candle WHERE instrument_id = :id AND timeframe = :tf")
                .param("id", instrumentId).param("tf", timeframe.name()).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
    }

    public int pruneBefore(LocalDate cutoff) {
        return jdbc.sql("DELETE FROM market_candle WHERE open_time < :cutoff")
                .param("cutoff", cutoff.atStartOfDay().atOffset(ZoneOffset.UTC)).update();
    }

    private SqlParameterSource params(Candle c) {
        return new MapSqlParameterSource()
                .addValue("instrumentId", c.instrumentId())
                .addValue("timeframe", c.timeframe().name())
                .addValue("openTime", ts(c.openTime()))
                .addValue("open", c.open())
                .addValue("high", c.high())
                .addValue("low", c.low())
                .addValue("close", c.close())
                .addValue("volume", c.volume())
                .addValue("oi", c.oi())
                .addValue("synthetic", c.synthetic());
    }

    private Candle map(ResultSet rs, int i) throws SQLException {
        return new Candle(
                rs.getObject("instrument_id", UUID.class),
                Timeframe.valueOf(rs.getString("timeframe")),
                rs.getObject("open_time", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"),
                rs.getLong("volume"), rs.getLong("oi"), rs.getBoolean("synthetic"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
