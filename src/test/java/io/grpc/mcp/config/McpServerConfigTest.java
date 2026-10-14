package io.grpc.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.mcp.tool.ToolResults;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.grpc.mcp.mcp.tool.McpTool;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpServerConfigTest {

    private McpServerConfig config;
    private McpProperties mcpProperties;

    @BeforeEach
    void setUp() {
        config = new McpServerConfig();
        mcpProperties = new McpProperties();
        mcpProperties.setApiKey("test-key");
        mcpProperties.setName("grpc-mcp-test");
    }

    @Test
    void connectionContextExtractor_resolvesConnectionFromHeaders() {
        // Arrange
        var env = new GrpcProperties();
        var request = mock(HttpServletRequest.class);
        when(request.getHeader("GRPC-Host")).thenReturn("target-host");
        when(request.getHeader("GRPC-Port")).thenReturn("443");
        when(request.getHeader("GRPC-Negotiation")).thenReturn("TLS");
        when(request.getHeader("GRPC-ProtoFile")).thenReturn("/home/u/svc.proto");

        var extractor = McpServerConfig.connectionContextExtractor(env, new ObjectMapper());

        // Act
        McpTransportContext context = extractor.extract(request);

        // Assert
        var connection = (GrpcConnection) context.get(GrpcConnection.CONTEXT_KEY);
        assertThat(connection).isNotNull();
        assertThat(connection.target()).isEqualTo("target-host:443");
        assertThat(connection.negotiation()).isEqualTo("TLS");
        assertThat(connection.protoRef()).isEqualTo("/home/u/svc.proto");
    }

    @Test
    void connectionContextExtractor_fallsBackToEnv() {
        // Arrange
        var env = new GrpcProperties();
        env.setAddress("env-host:50051");
        var request = mock(HttpServletRequest.class);
        lenient().when(request.getHeader("GRPC-Host")).thenReturn(null);
        lenient().when(request.getHeader("GRPC-Port")).thenReturn(null);
        lenient().when(request.getHeader("GRPC-Negotiation")).thenReturn(null);
        lenient().when(request.getHeader("GRPC-ProtoFile")).thenReturn(null);
        lenient().when(request.getHeader("GRPC-Address")).thenReturn(null);

        var extractor = McpServerConfig.connectionContextExtractor(env, new ObjectMapper());

        // Act
        McpTransportContext context = extractor.extract(request);

        // Assert
        var connection = (GrpcConnection) context.get(GrpcConnection.CONTEXT_KEY);
        assertThat(connection.target()).isEqualTo("env-host:50051");
    }

    @Test
    void toolSpecifications_mapsTools() {
        // Arrange
        McpTool t1 = mock(McpTool.class);
        McpTool t2 = mock(McpTool.class);
        when(t1.build()).thenReturn(spec("a"));
        when(t2.build()).thenReturn(spec("b"));

        // Act
        var specs = config.toolSpecifications(List.of(t1, t2));

        // Assert
        assertThat(specs).hasSize(2);
    }

    @Test
    void mcpServer_bean_createsServer() {
        // Arrange
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp").build();

        // Act
        var server = config.mcpServer(transport, mcpProperties, List.of());

        // Assert
        assertThat(server).isNotNull();
    }

    @Test
    void mcpServer_beanDestroy_close_doesNotThrow() {
        // Arrange
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp").build();
        var server = config.mcpServer(transport, mcpProperties, List.of());

        // Act
        assertThatCode(server::close).doesNotThrowAnyException();
    }

    private McpServerFeatures.SyncToolSpecification spec(String name) {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder(name, Map.of("type", "object")).build())
                .callHandler((exchange, request) -> ToolResults.success("ok"))
                .build();
    }
}
