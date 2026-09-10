package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A function the model may call: name, description and the JSON schema of its arguments. */
public record LlmTool(String name, String description, JsonNode parameters) {
}
