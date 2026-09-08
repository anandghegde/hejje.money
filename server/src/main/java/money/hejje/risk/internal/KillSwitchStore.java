package money.hejje.risk.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import money.hejje.common.ExecutionMode;
import money.hejje.risk.KillSwitchState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KillSwitchStore {

    private final JdbcClient jdbc;

    KillSwitchStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public KillSwitchState find(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM kill_switch WHERE mode = :mode").param("mode", mode.name()).query(this::map).single();
    }

    public void setStopNewOrders(ExecutionMode mode, boolean stop, String setBy, String reason, Instant now) {
        jdbc.sql("UPDATE kill_switch SET stop_new_orders = :stop, set_at = :setAt, set_by = :setBy, reason = :reason WHERE mode = :mode")
                .param("stop", stop).param("setAt", stop ? now.atOffset(ZoneOffset.UTC) : null)
                .param("setBy", stop ? setBy : null).param("reason", stop ? reason : null).param("mode", mode.name()).update();
    }

    private KillSwitchState map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime setAt = rs.getObject("set_at", OffsetDateTime.class);
        return new KillSwitchState(ExecutionMode.valueOf(rs.getString("mode")), rs.getBoolean("stop_new_orders"),
                setAt == null ? null : setAt.toInstant(), rs.getString("set_by"), rs.getString("reason"));
    }
}
