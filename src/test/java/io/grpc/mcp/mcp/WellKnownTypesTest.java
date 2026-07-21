package io.grpc.mcp.mcp;

import io.grpc.mcp.config.GrpcProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WellKnownTypesTest {

    private WellKnownTypes types;

    @BeforeEach
    void setUp() {
        var props = new GrpcProperties();
        types = new WellKnownTypes(props);
        types.init();
    }

    @Test
    void getSchemaFormat_timestamp() {
        assertThat(types.getSchemaFormat("google.protobuf.Timestamp")).isEqualTo("date-time");
    }

    @Test
    void getSchemaFormat_date() {
        assertThat(types.getSchemaFormat("google.type.Date")).isEqualTo("date");
    }

    @Test
    void getSchemaFormat_duration() {
        assertThat(types.getSchemaFormat("google.protobuf.Duration")).isEqualTo("duration");
    }

    @Test
    void getSchemaFormat_unknownType() {
        assertThat(types.getSchemaFormat("my.CustomMessage")).isNull();
    }

    @Test
    void getSchemaFormat_emptyType() {
        assertThat(types.getSchemaFormat("")).isNull();
    }

    @Test
    void getExample_timestamp() {
        assertThat(types.getExample("google.protobuf.Timestamp")).isEqualTo("2024-01-01T00:00:00Z");
    }

    @Test
    void getExample_date() {
        assertThat(types.getExample("google.type.Date")).isEqualTo("2024-01-01");
    }

    @Test
    void getExample_duration() {
        assertThat(types.getExample("google.protobuf.Duration")).isEqualTo("1s");
    }

    @Test
    void getExample_unknownType() {
        assertThat(types.getExample("my.CustomMessage")).isNull();
    }

    @Test
    void getExample_emptyType() {
        assertThat(types.getExample("")).isNull();
    }

}
