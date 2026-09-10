package money.hejje.agent;

/** An expected tool failure with the status to report (unknown id, bad argument combination, service unavailable). */
public class ToolException extends RuntimeException {

    private final ToolStatus status;

    public ToolException(ToolStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ToolStatus status() {
        return status;
    }

    public static ToolException notFound(String message) {
        return new ToolException(ToolStatus.NOT_FOUND, message);
    }

    public static ToolException invalid(String message) {
        return new ToolException(ToolStatus.INVALID_INPUT, message);
    }

    public static ToolException unavailable(String message) {
        return new ToolException(ToolStatus.UNAVAILABLE, message);
    }

    public static ToolException conflict(String message) {
        return new ToolException(ToolStatus.CONFLICT, message);
    }

    public static ToolException failed(String message) {
        return new ToolException(ToolStatus.FAILED, message);
    }
}
