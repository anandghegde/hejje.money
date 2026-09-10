package money.hejje.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validation of a JSON-schema subset: {@code type} (a name or a list of names), {@code enum}, {@code minimum}/{@code maximum},
 * {@code minLength}/{@code maxLength}, {@code format} ({@code uuid}, {@code date}), {@code properties}, {@code required},
 * {@code additionalProperties: false}, {@code items}, {@code minItems}/{@code maxItems}. Used by {@link StructuredOutput}
 * and by the agent tool registry. Pure.
 */
public final class JsonSchemaLite {

    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private JsonSchemaLite() {
    }

    public static List<String> validate(JsonNode schema, JsonNode value, String path) {
        List<String> errors = new ArrayList<>();
        validate(schema, value, path, errors);
        return errors;
    }

    private static void validate(JsonNode schema, JsonNode value, String path, List<String> errors) {
        JsonNode type = schema.path("type");
        if (type.isTextual() && !matches(type.asText(), value)) {
            errors.add(path + ": expected " + type.asText());
            return;
        }
        if (type.isArray()) {
            boolean any = false;
            for (JsonNode t : type) {
                any |= matches(t.asText(), value);
            }
            if (!any) {
                errors.add(path + ": expected one of " + type);
                return;
            }
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
        if (value.isTextual()) {
            String text = value.asText();
            if (schema.has("minLength") && text.length() < schema.get("minLength").asInt()) {
                errors.add(path + ": shorter than " + schema.get("minLength") + " characters");
            }
            if (schema.has("maxLength") && text.length() > schema.get("maxLength").asInt()) {
                errors.add(path + ": longer than " + schema.get("maxLength") + " characters");
            }
            String format = schema.path("format").asText("");
            if (format.equals("uuid") && !UUID_PATTERN.matcher(text).matches()) {
                errors.add(path + ": not a UUID");
            }
            if (format.equals("date")) {
                try {
                    LocalDate.parse(text);
                } catch (DateTimeParseException e) {
                    errors.add(path + ": not an ISO date (yyyy-mm-dd)");
                }
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
            if (schema.path("additionalProperties").isBoolean() && !schema.get("additionalProperties").asBoolean()) {
                value.fieldNames().forEachRemaining(name -> {
                    if (!props.has(name)) {
                        errors.add(path + ": unknown property " + name);
                    }
                });
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + ": fewer than " + schema.get("minItems") + " items");
            }
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) {
                errors.add(path + ": more than " + schema.get("maxItems") + " items");
            }
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
