package money.hejje.agent;

/** Runs a tool with its already validated, typed input. Throw {@link ToolException} for expected failures. */
@FunctionalInterface
public interface ToolHandler {
    Object handle(Object input, ToolContext context);
}
