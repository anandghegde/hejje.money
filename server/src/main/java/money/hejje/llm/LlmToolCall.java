package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool call requested by the model; {@code arguments} is the parsed JSON object (unvalidated). */
public record LlmToolCall(String id, String name, JsonNode arguments) {
}
