package money.hejje.execution.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import money.hejje.execution.ReconciliationIssue;
import money.hejje.execution.ReconciliationSeverity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationIssueStore {

    private final JdbcClient jdbc;

    ReconciliationIssueStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ReconciliationIssue issue) {
        jdbc.sql("""
                INSERT INTO reconciliation_issue (id, kind, severity, instrument_id, order_id, expected, observed, detail, detected_at, broker)
                VALUES (:id, :kind, :severity, :instrument, :order, :expected, :observed, :detail, :detectedAt, :broker)
                """)
                .param("id", issue.id()).param("kind", issue.kind()).param("severity", issue.severity().name())
                .param("instrument", issue.instrumentId(), Types.OTHER).param("order", issue.orderId(), Types.OTHER)
                .param("expected", issue.expected()).param("observed", issue.observed()).param("detail", issue.detail())
                .param("detectedAt", ts(issue.detectedAt())).param("broker", issue.broker()).update();
    }

    public List<ReconciliationIssue> open() {
        return jdbc.sql("SELECT * FROM reconciliation_issue WHERE resolved_at IS NULL ORDER BY detected_at DESC").query(this::map).list();
    }

    public List<ReconciliationIssue> openBySeverity(ReconciliationSeverity severity) {
        return jdbc.sql("SELECT * FROM reconciliation_issue WHERE resolved_at IS NULL AND severity = :s ORDER BY detected_at DESC")
                .param("s", severity.name()).query(this::map).list();
    }

    public Optional<ReconciliationIssue> findById(UUID id) {
        return jdbc.sql("SELECT * FROM reconciliation_issue WHERE id = :id").param("id", id).query(this::map).optional();
    }

    public boolean hasOpenLike(String kind, UUID instrumentId, UUID orderId) {
        return jdbc.sql("""
                SELECT count(*) FROM reconciliation_issue WHERE resolved_at IS NULL AND kind = :kind
                  AND instrument_id IS NOT DISTINCT FROM :instrument AND order_id IS NOT DISTINCT FROM :order
                """)
                .param("kind", kind).param("instrument", instrumentId, Types.OTHER).param("order", orderId, Types.OTHER)
                .query(Long.class).single() > 0;
    }

    public int resolve(UUID id, Instant now) {
        return jdbc.sql("UPDATE reconciliation_issue SET resolved_at = :now WHERE id = :id AND resolved_at IS NULL")
                .param("now", ts(now)).param("id", id).update();
    }

    public int resolveMatching(String kind, UUID instrumentId, Instant now) {
        return jdbc.sql("UPDATE reconciliation_issue SET resolved_at = :now WHERE resolved_at IS NULL AND kind = :kind AND instrument_id IS NOT DISTINCT FROM :instrument")
                .param("now", ts(now)).param("kind", kind).param("instrument", instrumentId, Types.OTHER).update();
    }

    private ReconciliationIssue map(ResultSet rs, int i) throws SQLException {
        OffsetDateTime resolved = rs.getObject("resolved_at", OffsetDateTime.class);
        return new ReconciliationIssue(rs.getObject("id", UUID.class), rs.getString("kind"),
                ReconciliationSeverity.valueOf(rs.getString("severity")), rs.getObject("instrument_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("expected"), rs.getString("observed"), rs.getString("detail"),
                rs.getObject("detected_at", OffsetDateTime.class).toInstant(), resolved == null ? null : resolved.toInstant(), rs.getString("broker"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
