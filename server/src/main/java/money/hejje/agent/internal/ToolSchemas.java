package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * JSON schema of a tool's output record, generated from its components so the documented schema and the validated
 * output can never drift. Primitive components are required; reference components are optional (tool outputs are
 * serialized without nulls).
 */
public final class ToolSchemas {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;

    private ToolSchemas() {
    }

    public static JsonNode of(Class<?> type) {
        return schema(type, 0);
    }

    private static ObjectNode schema(Type type, int depth) {
        ObjectNode s = F.objectNode();
        if (depth > MAX_DEPTH) {
            return s;
        }
        Class<?> raw = type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : type instanceof Class<?> c ? c : Object.class;
        if (raw == String.class || raw == Instant.class || raw == OffsetDateTime.class || raw == LocalTime.class) {
            return s.put("type", "string");
        }
        if (raw == UUID.class) {
            return s.put("type", "string").put("format", "uuid");
        }
        if (raw == LocalDate.class) {
            return s.put("type", "string").put("format", "date");
        }
        if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class || raw == short.class || raw == Short.class) {
            return s.put("type", "integer");
        }
        if (raw == double.class || raw == Double.class || raw == float.class || raw == Float.class || raw == BigDecimal.class) {
            return s.put("type", "number");
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return s.put("type", "boolean");
        }
        if (raw.isEnum()) {
            s.put("type", "string");
            for (Object constant : raw.getEnumConstants()) {
                s.withArray("enum").add(((Enum<?>) constant).name());
            }
            return s;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            s.put("type", "array");
            if (type instanceof ParameterizedType p) {
                s.set("items", schema(p.getActualTypeArguments()[0], depth + 1));
            }
            return s;
        }
        if (Map.class.isAssignableFrom(raw)) {
            return s.put("type", "object");
        }
        if (raw.isRecord()) {
            s.put("type", "object");
            ObjectNode props = s.putObject("properties");
            for (RecordComponent c : raw.getRecordComponents()) {
                props.set(c.getName(), schema(c.getGenericType(), depth + 1));
                if (c.getType().isPrimitive()) {
                    s.withArray("required").add(c.getName());
                }
            }
            return s;
        }
        return s; // JsonNode, Object: anything
    }
}
