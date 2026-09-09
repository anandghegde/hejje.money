package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Asks a profile for a JSON answer that must satisfy a (small-subset) JSON schema: {@code type}, {@code properties},
 * {@code required}, {@code enum}, {@code minimum}/{@code maximum}, {@code items}. An invalid answer is retried once with
 * the validation errors appended to the prompt; a second failure throws.
 */
@Component
public class StructuredOutput {

    private final LlmService llm;
    private final ObjectMapper json;

    StructuredOutput(LlmService llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    public JsonNode ask(String profile, String purpose, String promptVersion, String systemPrompt, String userPrompt, JsonNode schema) {
        LlmRequest request = new LlmRequest(profile, purpose, promptVersion, systemPrompt, userPrompt, null, null, true);
        LlmResponse first = llm.complete(request);
        JsonNode parsed = parse(first.text());
        List<String> errors = parsed == null ? List.of("not a JSON object") : JsonSchemaLite.validate(schema, parsed, "$");
        if (errors.isEmpty()) {
            return parsed;
        }
        String fix = userPrompt + "\n\nYour previous answer was invalid: " + String.join("; ", errors)
                + ". Reply again with only a JSON object that satisfies this schema:\n" + schema.toString();
        LlmResponse second = llm.complete(new LlmRequest(profile, purpose, promptVersion, systemPrompt, fix, null, null, true));
        JsonNode retried = parse(second.text());
        List<String> again = retried == null ? List.of("not a JSON object") : JsonSchemaLite.validate(schema, retried, "$");
        if (!again.isEmpty()) {
            throw new LlmException("Structured output invalid after retry: " + String.join("; ", again), false);
        }
        return retried;
    }

    /** Extracts the first JSON object from the text (models sometimes wrap it in prose or fences). */
    JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(t.substring(start, end + 1));
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
