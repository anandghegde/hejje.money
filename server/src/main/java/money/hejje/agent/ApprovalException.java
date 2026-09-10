package money.hejje.agent;

import java.util.List;
import java.util.UUID;

/** Approval endpoint failures, mapped to problem+json (404, 409, 403, 422). */
public class ApprovalException extends RuntimeException {

    private final List<String> reasons;

    protected ApprovalException(String message, List<String> reasons) {
        super(message);
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }

    public static class NotFound extends ApprovalException {
        public NotFound(UUID id) {
            super("Approval " + id + " not found", List.of());
        }
    }

    public static class Conflict extends ApprovalException {
        public Conflict(String message) {
            super(message, List.of());
        }
    }

    public static class Forbidden extends ApprovalException {
        public Forbidden(String message) {
            super(message, List.of());
        }
    }

    /** Policy or risk no longer allows the action at approval time. */
    public static class Revalidation extends ApprovalException {
        public Revalidation(String message, List<String> reasons) {
            super(message, reasons);
        }
    }
}
