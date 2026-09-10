package money.hejje.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JsonSchemaLiteTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static JsonNode j(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void additionalPropertiesFormatsLengthsItemCountsAndTypeLists() throws Exception {
        JsonNode schema = j("""
                {"type":"object","properties":{"id":{"type":"string","format":"uuid"},"day":{"type":"string","format":"date"},
                 "name":{"type":"string","minLength":2,"maxLength":4},"tags":{"type":"array","items":{"type":"string"},"minItems":1,"maxItems":2},
                 "note":{"type":["string","null"]}},"required":["id"],"additionalProperties":false}""");
        assertThat(JsonSchemaLite.validate(schema, j("""
                {"id":"0192f0c4-1f7a-7000-8000-000000000001","day":"2026-09-10","name":"abc","tags":["x"],"note":null}"""), "$")).isEmpty();
        assertThat(JsonSchemaLite.validate(schema, j("""
                {"id":"nope","day":"10/09/2026","name":"a","tags":[],"note":3,"extra":true}"""), "$"))
                .containsExactlyInAnyOrder("$.id: not a UUID", "$.day: not an ISO date (yyyy-mm-dd)", "$.name: shorter than 2 characters",
                        "$.tags: fewer than 1 items", "$.note: expected one of [\"string\",\"null\"]", "$: unknown property extra");
        assertThat(JsonSchemaLite.validate(schema, j("{\"id\":\"0192f0c4-1f7a-7000-8000-000000000001\",\"name\":\"abcde\",\"tags\":[\"a\",\"b\",\"c\"]}"), "$"))
                .containsExactlyInAnyOrder("$.name: longer than 4 characters", "$.tags: more than 2 items");
    }
}
