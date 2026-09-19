package money.hejje.instruments.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.broker.BrokerInstrument;
import money.hejje.common.Exchange;
import money.hejje.common.Ids;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;
import money.hejje.instruments.BrokerInstrumentMapping;
import money.hejje.instruments.HejjeSymbol;
import money.hejje.instruments.Instrument;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int BATCH = 1000;

    private static final String UPSERT_INSTRUMENT = """
            INSERT INTO instrument (id, symbol, name, exchange, type, underlying, expiry, strike, option_type, lot_size, tick_size, isin, active, updated_at)
            VALUES (:id, :symbol, :name, :exchange, :type, :underlying, :expiry, :strike, :optionType, :lotSize, :tickSize, :isin, TRUE, :now)
            ON CONFLICT (exchange, symbol, type, expiry, strike, option_type) DO UPDATE SET
                name = EXCLUDED.name, underlying = EXCLUDED.underlying, lot_size = EXCLUDED.lot_size,
                tick_size = EXCLUDED.tick_size, isin = COALESCE(EXCLUDED.isin, instrument.isin), active = TRUE, updated_at = EXCLUDED.updated_at
            """;

    private static final String UPSERT_MAPPING = """
            INSERT INTO broker_instrument_mapping (id, instrument_id, broker, broker_token, trading_symbol, exchange_segment, raw, synced_at)
            SELECT :id, i.id, :broker, :brokerToken, :tradingSymbol, :exchangeSegment, CAST(:raw AS jsonb), :now
            FROM instrument i
            WHERE i.exchange = :exchange AND i.symbol = :symbol AND i.type = :type
              AND i.expiry IS NOT DISTINCT FROM :expiry AND i.strike IS NOT DISTINCT FROM :strike
              AND i.option_type IS NOT DISTINCT FROM :optionType
            ON CONFLICT (broker, broker_token) DO UPDATE SET
                instrument_id = EXCLUDED.instrument_id, trading_symbol = EXCLUDED.trading_symbol,
                exchange_segment = EXCLUDED.exchange_segment, raw = EXCLUDED.raw, synced_at = EXCLUDED.synced_at
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;

    InstrumentStore(JdbcClient jdbc, NamedParameterJdbcTemplate named, ObjectMapper json) {
        this.jdbc = jdbc;
        this.named = named;
        this.json = json;
    }

    /** Upserts instruments and their broker mappings in batches. Returns the number of instrument rows written. */
    public int upsertAll(String broker, List<BrokerInstrument> rows, Instant now) {
        int written = 0;
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<BrokerInstrument> chunk = rows.subList(start, Math.min(rows.size(), start + BATCH));
            SqlParameterSource[] instrumentParams = chunk.stream().map(r -> instrumentParams(r, now)).toArray(SqlParameterSource[]::new);
            named.batchUpdate(UPSERT_INSTRUMENT, instrumentParams);
            SqlParameterSource[] mappingParams = chunk.stream().map(r -> mappingParams(broker, r, now)).toArray(SqlParameterSource[]::new);
            named.batchUpdate(UPSERT_MAPPING, mappingParams);
            written += chunk.size();
        }
        return written;
    }

    /** Deactivates instruments that carry no mapping for {@code broker} refreshed at or after {@code runStart}. */
    public int deactivateMissing(String broker, Instant runStart, Instant now) {
        return jdbc.sql("""
                UPDATE instrument i SET active = FALSE, updated_at = :now
                WHERE i.active AND NOT EXISTS (
                    SELECT 1 FROM broker_instrument_mapping m
                    WHERE m.instrument_id = i.id AND m.broker = :broker AND m.synced_at >= :runStart)
                """)
                .param("now", ts(now)).param("broker", broker).param("runStart", ts(runStart))
                .update();
    }

    /** Every instrument, active or not (the master a SIM instance imports, plan M7.2). */
    public List<Instrument> all() {
        return jdbc.sql("SELECT * FROM instrument ORDER BY id").query(this::map).list();
    }

    /**
     * Imports instruments keeping their ids (so the Parquet history, keyed by instrument id, applies) and maps each to
     * {@code broker} with the id as its token. Rows already present by id are left alone.
     */
    public int importWithIds(List<Instrument> rows, String broker, Instant now) {
        int written = 0;
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<Instrument> chunk = rows.subList(start, Math.min(rows.size(), start + BATCH));
            SqlParameterSource[] params = chunk.stream().map(i -> new MapSqlParameterSource()
                    .addValue("id", i.id()).addValue("symbol", i.symbol()).addValue("name", i.name()).addValue("exchange", i.exchange().name())
                    .addValue("type", i.type().name()).addValue("underlying", i.underlying()).addValue("expiry", i.expiry(), Types.DATE)
                    .addValue("strike", i.strike(), Types.NUMERIC).addValue("optionType", i.optionType() == null ? null : i.optionType().name(), Types.VARCHAR)
                    .addValue("lotSize", i.lotSize()).addValue("tickSize", i.tickSize()).addValue("isin", i.isin(), Types.VARCHAR)
                    .addValue("active", i.active()).addValue("now", ts(now)).addValue("mappingId", Ids.newId()).addValue("broker", broker)
                    .addValue("token", i.id().toString())).toArray(SqlParameterSource[]::new);
            named.batchUpdate("""
                    INSERT INTO instrument (id, symbol, name, exchange, type, underlying, expiry, strike, option_type, lot_size, tick_size, isin, active, updated_at)
                    VALUES (:id, :symbol, :name, :exchange, :type, :underlying, :expiry, :strike, :optionType, :lotSize, :tickSize, :isin, :active, :now)
                    ON CONFLICT (id) DO NOTHING
                    """, params);
            named.batchUpdate("""
                    INSERT INTO broker_instrument_mapping (id, instrument_id, broker, broker_token, trading_symbol, exchange_segment, raw, synced_at)
                    VALUES (:mappingId, :id, :broker, :token, :symbol, :exchange, CAST('{}' AS jsonb), :now)
                    ON CONFLICT (broker, broker_token) DO NOTHING
                    """, params);
            written += chunk.size();
        }
        return written;
    }

    public long countActive() {
        return jdbc.sql("SELECT count(*) FROM instrument WHERE active").query(Long.class).single();
    }

    public Optional<Instrument> findById(UUID id) {
        return jdbc.sql("SELECT * FROM instrument WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<Instrument> findByKey(HejjeSymbol key) {
        return jdbc.sql("""
                SELECT * FROM instrument WHERE exchange = :exchange AND symbol = :symbol AND type = :type
                  AND expiry IS NOT DISTINCT FROM :expiry AND strike IS NOT DISTINCT FROM :strike
                  AND option_type IS NOT DISTINCT FROM :optionType
                """)
                .param("exchange", key.exchange().name()).param("symbol", key.symbol()).param("type", key.type().name())
                .param("expiry", key.expiry(), Types.DATE)
                .param("strike", key.strike() == null ? null : key.strike().setScale(2), Types.NUMERIC)
                .param("optionType", key.optionType() == null ? null : key.optionType().name(), Types.VARCHAR)
                .query(this::map).optional();
    }

    public Optional<Instrument> findByTradingSymbol(String exchangeSegment, String tradingSymbol) {
        return jdbc.sql("""
                SELECT i.* FROM instrument i JOIN broker_instrument_mapping m ON m.instrument_id = i.id
                WHERE m.exchange_segment = :segment AND m.trading_symbol = :symbol ORDER BY i.active DESC LIMIT 1
                """)
                .param("segment", exchangeSegment).param("symbol", tradingSymbol).query(this::map).optional();
    }

    public Optional<Instrument> findByTradingSymbol(String broker, String exchangeSegment, String tradingSymbol) {
        return jdbc.sql("""
                SELECT i.* FROM instrument i JOIN broker_instrument_mapping m ON m.instrument_id = i.id
                WHERE m.broker = :broker AND m.exchange_segment = :segment AND m.trading_symbol = :symbol LIMIT 1
                """)
                .param("broker", broker).param("segment", exchangeSegment).param("symbol", tradingSymbol).query(this::map).optional();
    }

    public Optional<Instrument> findByBrokerToken(String broker, String brokerToken) {
        return jdbc.sql("""
                SELECT i.* FROM instrument i JOIN broker_instrument_mapping m ON m.instrument_id = i.id
                WHERE m.broker = :broker AND m.broker_token = :token
                """)
                .param("broker", broker).param("token", brokerToken).query(this::map).optional();
    }

    public Optional<BrokerInstrumentMapping> findMapping(UUID instrumentId, String broker) {
        return jdbc.sql("SELECT * FROM broker_instrument_mapping WHERE instrument_id = :id AND broker = :broker")
                .param("id", instrumentId).param("broker", broker).query(this::mapMapping).optional();
    }

    public List<Instrument> search(String q, Exchange exchange, InstrumentType type, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM instrument WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (!q.isEmpty()) {
            sql.append(" AND (upper(symbol) LIKE :prefix OR upper(name) LIKE :contains OR upper(underlying) LIKE :prefix)");
            params.addValue("prefix", q.toUpperCase() + "%").addValue("contains", "%" + q.toUpperCase() + "%");
        }
        if (exchange != null) {
            sql.append(" AND exchange = :exchange");
            params.addValue("exchange", exchange.name());
        }
        if (type != null) {
            sql.append(" AND type = :type");
            params.addValue("type", type.name());
        }
        sql.append(" ORDER BY active DESC, length(symbol), symbol, type, expiry NULLS FIRST, strike NULLS FIRST, option_type NULLS FIRST LIMIT :limit");
        params.addValue("limit", limit);
        return named.query(sql.toString(), params, this::map);
    }

    public Optional<Instrument> nearestFuture(String underlying, LocalDate asOf) {
        return jdbc.sql("""
                SELECT * FROM instrument WHERE type = 'FUT' AND active AND underlying = :underlying AND expiry >= :asOf
                ORDER BY expiry LIMIT 1
                """)
                .param("underlying", underlying).param("asOf", asOf).query(this::map).optional();
    }

    /** Every future on the underlying (active or not), oldest expiry first. */
    public List<Instrument> futures(String underlying) {
        return jdbc.sql("SELECT * FROM instrument WHERE type = 'FUT' AND underlying = :underlying ORDER BY expiry")
                .param("underlying", underlying).query(this::map).list();
    }

    public List<Instrument> optionChain(String underlying, LocalDate expiry) {
        return jdbc.sql("""
                SELECT * FROM instrument WHERE type = 'OPT' AND active AND underlying = :underlying AND expiry = :expiry
                ORDER BY strike, option_type
                """)
                .param("underlying", underlying).param("expiry", expiry).query(this::map).list();
    }

    public List<LocalDate> optionExpiries(String underlying, LocalDate from) {
        return jdbc.sql("""
                SELECT DISTINCT expiry FROM instrument WHERE type = 'OPT' AND active AND underlying = :underlying AND expiry >= :from
                ORDER BY expiry
                """)
                .param("underlying", underlying).param("from", from).query(LocalDate.class).list();
    }

    private SqlParameterSource instrumentParams(BrokerInstrument r, Instant now) {
        return new MapSqlParameterSource()
                .addValue("id", Ids.newId())
                .addValue("symbol", r.symbol())
                .addValue("name", r.name())
                .addValue("exchange", r.exchange().name())
                .addValue("type", r.type().name())
                .addValue("underlying", r.underlying())
                .addValue("expiry", r.expiry(), Types.DATE)
                .addValue("strike", r.strike(), Types.NUMERIC)
                .addValue("optionType", r.optionType() == null ? null : r.optionType().name(), Types.VARCHAR)
                .addValue("lotSize", r.lotSize())
                .addValue("tickSize", r.tickSize())
                .addValue("isin", r.isin(), Types.VARCHAR)
                .addValue("now", ts(now));
    }

    private SqlParameterSource mappingParams(String broker, BrokerInstrument r, Instant now) {
        return new MapSqlParameterSource()
                .addValue("id", Ids.newId())
                .addValue("broker", broker)
                .addValue("brokerToken", r.brokerToken())
                .addValue("tradingSymbol", r.tradingSymbol())
                .addValue("exchangeSegment", r.exchangeSegment())
                .addValue("raw", toJson(r.raw()))
                .addValue("now", ts(now))
                .addValue("exchange", r.exchange().name())
                .addValue("symbol", r.symbol())
                .addValue("type", r.type().name())
                .addValue("expiry", r.expiry(), Types.DATE)
                .addValue("strike", r.strike(), Types.NUMERIC)
                .addValue("optionType", r.optionType() == null ? null : r.optionType().name(), Types.VARCHAR);
    }

    private Instrument map(ResultSet rs, int i) throws SQLException {
        String optionType = rs.getString("option_type");
        return new Instrument(
                rs.getObject("id", UUID.class),
                rs.getString("symbol"),
                rs.getString("name"),
                Exchange.valueOf(rs.getString("exchange")),
                InstrumentType.valueOf(rs.getString("type")),
                rs.getString("underlying"),
                rs.getObject("expiry", LocalDate.class),
                rs.getBigDecimal("strike"),
                optionType == null ? null : OptionType.valueOf(optionType),
                rs.getInt("lot_size"),
                rs.getBigDecimal("tick_size"),
                rs.getString("isin"),
                rs.getBoolean("active"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private BrokerInstrumentMapping mapMapping(ResultSet rs, int i) throws SQLException {
        return new BrokerInstrumentMapping(
                rs.getObject("instrument_id", UUID.class),
                rs.getString("broker"),
                rs.getString("broker_token"),
                rs.getString("trading_symbol"),
                rs.getString("exchange_segment"),
                fromJson(rs.getString("raw")),
                rs.getObject("synced_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private String toJson(Map<String, String> raw) {
        try {
            return json.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("raw instrument row is not serializable", e);
        }
    }

    private Map<String, Object> fromJson(String text) {
        try {
            return text == null ? Map.of() : json.readValue(text, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored mapping raw is not valid JSON", e);
        }
    }
}
