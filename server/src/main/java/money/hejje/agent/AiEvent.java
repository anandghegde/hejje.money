package money.hejje.agent;

/** Streamed while a question is answered: each tool call, answer text deltas (per LLM step), and the final turn. */
public sealed interface AiEvent {

    record ToolCalled(AiTraceStep step) implements AiEvent {}

    /** Text streamed by LLM step {@code step}; a later step's text replaces an earlier step's. */
    record Delta(int step, String text) implements AiEvent {}

    record Done(AiTurn turn) implements AiEvent {}
}
