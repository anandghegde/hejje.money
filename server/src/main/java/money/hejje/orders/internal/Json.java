package money.hejje.orders.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Small JSON helpers shared by the orders stores. */
final class Json {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final ObjectMapper mapper;

    Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not serializable: " + value, e);
        }
    }

    Map<String, Object> readMap(String text) {
        try {
            return text == null ? Map.of() : mapper.readValue(text, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored JSON map", e);
        }
    }

    List<String> readStrings(String text) {
        try {
            return text == null ? List.of() : mapper.readValue(text, STRINGS);
        } catch (Exception e) {
            throw new IllegalStateException("Bad stored JSON list", e);
        }
    }
}
