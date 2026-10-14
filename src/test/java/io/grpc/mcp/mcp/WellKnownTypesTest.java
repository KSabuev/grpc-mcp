package io.grpc.mcp.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WellKnownTypesTest {

    private WellKnownTypes types;

    @BeforeEach
    void setUp() {
        types = new WellKnownTypes();
    }

    @Test
    void getSchemaFormat_timestamp() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getSchemaFormat("google.protobuf.Timestamp")).isEqualTo("date-time");
    }

    @Test
    void getSchemaFormat_date() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getSchemaFormat("google.type.Date")).isEqualTo("date");
    }

    @Test
    void getSchemaFormat_duration() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getSchemaFormat("google.protobuf.Duration")).isEqualTo("duration");
    }

    @Test
    void getSchemaFormat_unknownType() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getSchemaFormat("my.CustomMessage")).isNull();
    }

    @Test
    void getSchemaFormat_emptyType() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getSchemaFormat("")).isNull();
    }

    @Test
    void getExample_timestamp() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getExample("google.protobuf.Timestamp")).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    void getExample_date() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getExample("google.type.Date")).isEqualTo("2024-01-01");
    }

    @Test
    void getExample_duration() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getExample("google.protobuf.Duration")).isEqualTo("1s");
    }

    @Test
    void getExample_unknownType() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getExample("my.CustomMessage")).isNull();
    }

    @Test
    void getExample_emptyType() {
        // Arrange

        // Act

        // Assert
        assertThat(types.getExample("")).isNull();
    }

}
