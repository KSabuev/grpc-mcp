package io.grpc.mcp.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMapTest {

    @Test
    void entry_returnsMapWithType() {
        Map<String, Object> result = SchemaMap.entry("string");
        assertThat(result).containsExactly(Map.entry("type", "string"));
    }

    @Test
    void entry_withKeyValue() {
        Map<String, Object> result = SchemaMap.entry("integer", "format", "int64");
        assertThat(result)
                .containsEntry("type", "integer")
                .containsEntry("format", "int64");
    }

    @Test
    void entry_withNullValue_skipsNull() {
        Map<String, Object> result = SchemaMap.entry("string", "format", null);
        assertThat(result).containsOnly(Map.entry("type", "string"));
    }

    @Test
    void entry_withEmptyType() {
        Map<String, Object> result = SchemaMap.entry("");
        assertThat(result).containsEntry("type", "");
    }
}
