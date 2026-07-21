package io.grpc.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class McpServerConfigTest {

    private final McpServerConfig config = new McpServerConfig();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void metadataJson_isNull_createsInterceptor() {
        var props = new GrpcProperties();

        var interceptor = config.grpcMetadataInterceptor(props, mapper);

        assertThat(interceptor).isNotNull();
    }

    @Test
    void metadataJson_isValid_parsesWithoutError() {
        var props = new GrpcProperties();
        props.setMetadataJson("{\"Authorization\": \"Bearer token\", \"X-Custom\": \"value\"}");

        var interceptor = config.grpcMetadataInterceptor(props, mapper);

        assertThat(interceptor).isNotNull();
    }

    @Test
    void metadataJson_isInvalid_doesNotThrow() {
        var props = new GrpcProperties();
        props.setMetadataJson("{bad json}");

        var interceptor = config.grpcMetadataInterceptor(props, mapper);

        assertThat(interceptor).isNotNull();
    }

    @Test
    void metadataJson_isEmptyObject_doesNotThrow() {
        var props = new GrpcProperties();
        props.setMetadataJson("{}");

        var interceptor = config.grpcMetadataInterceptor(props, mapper);

        assertThat(interceptor).isNotNull();
    }

    @Test
    void mcpJsonMapper_returnsMapper() {
        var result = config.mcpJsonMapper();

        assertThat(result).isNotNull();
    }

    @Test
    void close_withNullServer_doesNotThrow() {
        assertThatCode(() -> config.close()).doesNotThrowAnyException();
    }
}
