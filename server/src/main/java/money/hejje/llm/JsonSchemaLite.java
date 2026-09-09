package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Validation of the JSON-schema subset {@link StructuredOutput} promises. Pure. */
public final class JsonSchemaLite {

    private JsonSchemaLite() {
    }

    public static List<String> validate(JsonNode schema, JsonNode value, String path) {
        List<String> errors = new ArrayList<>();
        validate(schema, value, path, errors);
        return errors;
    }

    private static void validate(JsonNode schema, JsonNode value, String path, List<String> errors) {
        String type = schema.path("type").asText(null);
        if (type != null && !matches(type, value)) {
            errors.add(path + ": expected " + type);
            return;
        }
        if (schema.has("enum")) {
            boolean ok = false;
            for (JsonNode allowed : schema.get("enum")) {
                if (allowed.equals(value) || (value.isTextual() && allowed.isTextual() && allowed.asText().equals(value.asText()))) {
                    ok = true;
                }
            }
            if (!ok) {
                errors.add(path + ": not one of " + schema.get("enum"));
            }
        }
        if (value.isNumber()) {
            if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
                errors.add(path + ": below minimum " + schema.get("minimum"));
            }
            if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
                errors.add(path + ": above maximum " + schema.get("maximum"));
            }
        }
        if (value.isObject()) {
            for (JsonNode required : schema.path("required")) {
                if (!value.has(required.asText())) {
                    errors.add(path + ": missing " + required.asText());
                }
            }
            JsonNode props = schema.path("properties");
            props.fieldNames().forEachRemaining(name -> {
                if (value.has(name)) {
                    validate(props.get(name), value.get(name), path + "." + name, errors);
                }
            });
        }
        if (value.isArray() && schema.has("items")) {
            int i = 0;
            for (JsonNode item : value) {
                validate(schema.get("items"), item, path + "[" + i++ + "]", errors);
            }
        }
    }

    private static boolean matches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }
}
