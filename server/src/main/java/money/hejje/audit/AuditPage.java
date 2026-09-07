package money.hejje.audit;

import java.util.List;

public record AuditPage(List<AuditRecord> content, int page, int size, long total) {
}
