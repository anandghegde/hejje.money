package money.hejje.market.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.Exchange;
import money.hejje.market.ContinuousSeries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ContinuousSeriesStore {

    private static final TypeReference<List<ContinuousSeries.Segment>> SEGMENTS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ContinuousSeriesStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void upsert(ContinuousSeries s) {
        try {
            jdbc.sql("""
                    INSERT INTO continuous_series (id, underlying, exchange, symbol, lot_size, tick_size, segments, built_at)
                    VALUES (:id, :underlying, :exchange, :symbol, :lotSize, :tickSize, CAST(:segments AS jsonb), :builtAt)
                    ON CONFLICT (symbol) DO UPDATE SET lot_size = EXCLUDED.lot_size, tick_size = EXCLUDED.tick_size,
                        segments = EXCLUDED.segments, built_at = EXCLUDED.built_at
                    """)
                    .param("id", s.id()).param("underlying", s.underlying()).param("exchange", s.exchange().name()).param("symbol", s.symbol())
                    .param("lotSize", s.lotSize()).param("tickSize", s.tickSize()).param("segments", json.writeValueAsString(s.segments()))
                    .param("builtAt", s.builtAt().atOffset(ZoneOffset.UTC)).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public Optional<ContinuousSeries> findById(UUID id) {
        return jdbc.sql("SELECT * FROM continuous_series WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<ContinuousSeries> findBySymbol(String symbol) {
        return jdbc.sql("SELECT * FROM continuous_series WHERE symbol = :symbol").param("symbol", symbol).query(this::map).optional();
    }

    public List<ContinuousSeries> findAll() {
        return jdbc.sql("SELECT * FROM continuous_series ORDER BY symbol").query(this::map).list();
    }

    private ContinuousSeries map(ResultSet rs, int i) throws SQLException {
        try {
            return new ContinuousSeries(rs.getObject("id", UUID.class), rs.getString("underlying"), Exchange.valueOf(rs.getString("exchange")),
                    rs.getString("symbol"), rs.getInt("lot_size"), rs.getBigDecimal("tick_size"), json.readValue(rs.getString("segments"), SEGMENTS),
                    rs.getObject("built_at", java.time.OffsetDateTime.class).toInstant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
