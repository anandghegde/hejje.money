package money.hejje.swing.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.swing.SwingLimits;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code swing_limits}: one row per mode (plan M11.3). */
@Repository
public class SwingLimitsStore {

    private final JdbcClient jdbc;

    SwingLimitsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public SwingLimits find(ExecutionMode mode) {
        return jdbc.sql("SELECT * FROM swing_limits WHERE mode = :mode").param("mode", mode.name()).query(this::map).single();
    }

    public void update(SwingLimits l, String by) {
        jdbc.sql("""
                UPDATE swing_limits SET swing_capital_paise = :capital, max_open_positions = :maxOpen, max_risk_per_position_paise = :maxRisk,
                    gap_allowance_pct = :gap, max_overnight_risk_paise = :maxOvernight, max_positions_per_industry = :perIndustry,
                    block_before_events = :events, block_surveillance = :surveillance, updated_at = now(), updated_by = :by
                WHERE mode = :mode
                """).param("capital", l.swingCapital().paise()).param("maxOpen", l.maxOpenPositions()).param("maxRisk", l.maxRiskPerPosition().paise())
                .param("gap", l.gapAllowancePct()).param("maxOvernight", l.maxOvernightRisk().paise()).param("perIndustry", l.maxPositionsPerIndustry())
                .param("events", l.blockBeforeEvents()).param("surveillance", l.blockSurveillance()).param("by", by).param("mode", l.mode().name())
                .update();
    }

    private SwingLimits map(ResultSet rs, int i) throws SQLException {
        return new SwingLimits(ExecutionMode.valueOf(rs.getString("mode")), Money.ofPaise(rs.getLong("swing_capital_paise")), rs.getInt("max_open_positions"),
                Money.ofPaise(rs.getLong("max_risk_per_position_paise")), rs.getBigDecimal("gap_allowance_pct"),
                Money.ofPaise(rs.getLong("max_overnight_risk_paise")), rs.getInt("max_positions_per_industry"), rs.getBoolean("block_before_events"),
                rs.getBoolean("block_surveillance"));
    }
}
