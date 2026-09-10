package money.hejje.options.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.common.ActorType;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.options.OptionsPosition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OptionsStore {

    private static final TypeReference<List<OptionsPosition.Leg>> LEGS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    OptionsStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(OptionsPosition p, String idempotencyKey) {
        jdbc.sql("""
                INSERT INTO options_position (id, mode, client_id, idempotency_key, source, actor_id, strategy_id, version_id, deployment_id, signal_id, underlying,
                    underlying_instrument_id, direction, underlying_stop, basket_id, status, legs, combined_stop_paise, combined_target_paise, force_exit_time, product,
                    close_reason, realized_paise, detail, opened_at, closed_at, updated_at)
                VALUES (:id, :mode, :client, :key, :source, :actor, :strategy, :version, :deployment, :signal, :underlying, :underlyingId, :direction, :stop, :basket,
                    :status, CAST(:legs AS jsonb), :cstop, :ctarget, :forceExit, :product, :reason, :realized, :detail, :openedAt, :closedAt, :updatedAt)
                """)
                .param("id", p.id()).param("mode", p.mode().name()).param("client", p.clientId()).param("key", idempotencyKey).param("source", p.source().name())
                .param("actor", p.actorId()).param("strategy", p.strategyId()).param("version", p.versionId()).param("deployment", p.deploymentId())
                .param("signal", p.signalId()).param("underlying", p.underlying()).param("underlyingId", p.underlyingInstrumentId()).param("direction", p.direction().name())
                .param("stop", p.underlyingStop()).param("basket", p.basketId()).param("status", p.status().name()).param("legs", write(p.legs()))
                .param("cstop", p.combinedStop() == null ? null : p.combinedStop().paise()).param("ctarget", p.combinedTarget() == null ? null : p.combinedTarget().paise())
                .param("forceExit", p.forceExitTime().toString()).param("product", p.product().name()).param("reason", p.closeReason())
                .param("realized", p.realized() == null ? null : p.realized().paise()).param("detail", p.detail()).param("openedAt", ts(p.openedAt()))
                .param("closedAt", p.closedAt() == null ? null : ts(p.closedAt())).param("updatedAt", ts(p.updatedAt())).update();
    }

    public void update(OptionsPosition p) {
        jdbc.sql("""
                UPDATE options_position SET status = :status, legs = CAST(:legs AS jsonb), close_reason = :reason, realized_paise = :realized, detail = :detail,
                    closed_at = :closedAt, updated_at = :updatedAt WHERE id = :id
                """)
                .param("status", p.status().name()).param("legs", write(p.legs())).param("reason", p.closeReason())
                .param("realized", p.realized() == null ? null : p.realized().paise()).param("detail", p.detail())
                .param("closedAt", p.closedAt() == null ? null : ts(p.closedAt())).param("updatedAt", ts(p.updatedAt())).param("id", p.id()).update();
    }

    public Optional<OptionsPosition> find(UUID id) {
        return jdbc.sql("SELECT * FROM options_position WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public Optional<OptionsPosition> findByKey(UUID clientId, String key) {
        return jdbc.sql("SELECT * FROM options_position WHERE client_id = :c AND idempotency_key = :k").param("c", clientId).param("k", key).query(this::map).optional();
    }

    public List<OptionsPosition> active(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM options_position WHERE mode = :mode AND status IN ('PENDING', 'OPEN', 'CLOSING') ORDER BY opened_at")
                .param("mode", mode.name()).query(this::map).list();
    }

    public List<OptionsPosition> list(ExecutionMode mode, int limit) {
        return jdbc.sql("SELECT * FROM options_position WHERE mode = :mode ORDER BY opened_at DESC LIMIT :limit").param("mode", mode.name()).param("limit", limit)
                .query(this::map).list();
    }

    public int openedSince(UUID deploymentId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM options_position WHERE deployment_id = :d AND opened_at >= :since AND status <> 'FAILED'").param("d", deploymentId)
                .param("since", ts(since)).query(Integer.class).single();
    }

    public int closedPaper(UUID versionId) {
        return jdbc.sql("SELECT count(*) FROM options_position WHERE version_id = :v AND mode = 'PAPER' AND status = 'CLOSED'").param("v", versionId)
                .query(Integer.class).single();
    }

    private OptionsPosition map(ResultSet rs, int i) throws SQLException {
        Object cstop = rs.getObject("combined_stop_paise");
        Object ctarget = rs.getObject("combined_target_paise");
        Object realized = rs.getObject("realized_paise");
        return new OptionsPosition(rs.getObject("id", UUID.class), ExecutionMode.valueOf(rs.getString("mode")), rs.getObject("client_id", UUID.class),
                ActorType.valueOf(rs.getString("source")), rs.getString("actor_id"), rs.getObject("strategy_id", UUID.class), rs.getObject("version_id", UUID.class),
                rs.getObject("deployment_id", UUID.class), rs.getObject("signal_id", UUID.class), rs.getString("underlying"),
                rs.getObject("underlying_instrument_id", UUID.class), Side.valueOf(rs.getString("direction")), rs.getBigDecimal("underlying_stop"),
                rs.getObject("basket_id", UUID.class), OptionsPosition.Status.valueOf(rs.getString("status")), read(rs.getString("legs")),
                cstop == null ? null : Money.ofPaise(((Number) cstop).longValue()), ctarget == null ? null : Money.ofPaise(((Number) ctarget).longValue()),
                LocalTime.parse(rs.getString("force_exit_time")), Product.valueOf(rs.getString("product")), rs.getString("close_reason"),
                realized == null ? null : Money.ofPaise(((Number) realized).longValue()), rs.getString("detail"), instant(rs, "opened_at"), instant(rs, "closed_at"),
                instant(rs, "updated_at"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private List<OptionsPosition.Leg> read(String text) {
        try {
            return json.readValue(text, LEGS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(ResultSet rs, String c) throws SQLException {
        OffsetDateTime v = rs.getObject(c, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
