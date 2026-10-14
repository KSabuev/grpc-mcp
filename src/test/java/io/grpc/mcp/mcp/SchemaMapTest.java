package io.grpc.mcp.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMapTest {

    @Test
    void entry_returnsMapWithType() {
        // Arrange

        // Act
        Map<String, Object> result = SchemaMap.entry("string");

        // Assert
        assertThat(result).containsExactly(Map.entry("type", "string"));
    }

    @Test
    void entry_withKeyValue() {
        // Arrange

        // Act
        Map<String, Object> result = SchemaMap.entry("integer", "format", "int64");

        // Assert
        assertThat(result)
                .containsEntry("type", "integer")
                .containsEntry("format", "int64");
    }

    @Test
    void entry_withNullValue_skipsNull() {
        // Arrange

        // Act
        Map<String, Object> result = SchemaMap.entry("string", "format", null);

        // Assert
        assertThat(result).containsOnly(Map.entry("type", "string"));
    }

    @Test
    void entry_withEmptyType() {
        // Arrange

        // Act
        Map<String, Object> result = SchemaMap.entry("");

        // Assert
        assertThat(result).containsEntry("type", "");
    }
}
