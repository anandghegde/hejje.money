package money.hejje.agent;

/** Outcome of a tool call and the HTTP status it maps to on the direct-invocation endpoint. */
public enum ToolStatus {
    OK(200),
    INVALID_INPUT(400),
    FORBIDDEN(403),
    NOT_FOUND(404),
    UNKNOWN_TOOL(404),
    CONFLICT(409),
    FAILED(422),
    INVALID_OUTPUT(500),
    UNAVAILABLE(503);

    private final int httpStatus;

    ToolStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
