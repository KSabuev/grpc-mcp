package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.grpc.discovery.MethodMeta;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListMethodsToolTest {

    @Mock ConnectionProvider provider;

    private McpJsonMapper mapper;
    private ListMethodsTool tool;

    @BeforeEach
    void setUp() {
        mapper = McpJsonDefaults.getMapper();
        tool = new ListMethodsTool(provider, mapper);
    }

    @Test
    void returnsMethodNames() {
        // Arrange
        var connection = connection();
        when(provider.methods(eq(connection))).thenReturn(List.of(
                MethodMeta.builder().fullMethodName("pkg.Svc/m1").build(),
                MethodMeta.builder().fullMethodName("pkg.Svc/m2").build()));

        // Act
        var result = call(connection);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("pkg.Svc/m1").contains("pkg.Svc/m2");
    }

    @Test
    void emptyDiscovery_returnsEmptyList() {
        // Arrange
        var connection = connection();
        when(provider.methods(eq(connection))).thenReturn(List.of());

        // Act
        var result = call(connection);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("[]");
    }

    @Test
    void noTarget_returnsError() {
        // Arrange
        var connection = new GrpcConnection("", "PLAINTEXT", Map.of(), null);

        // Act
        var result = call(connection);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("GRPC-Host");
    }

    @Test
    void providerException_returnsError() {
        // Arrange
        var connection = connection();
        when(provider.methods(eq(connection))).thenThrow(new RuntimeException("backend down"));

        // Act
        var result = call(connection);

        // Assert
        assertThat(result.isError()).isTrue();
    }

    private McpSchema.CallToolResult call(GrpcConnection connection) {
        var handler = tool.build().callHandler();
        return handler.apply(exchange(connection), new McpSchema.CallToolRequest("list_methods", Map.of()));
    }

    static GrpcConnection connection() {
        return new GrpcConnection("client-host:50051", "PLAINTEXT", Map.of(), null);
    }

    static McpSyncServerExchange exchange(GrpcConnection connection) {
        var context = McpTransportContext.create(Map.of(GrpcConnection.CONTEXT_KEY, connection));
        var exchange = mock(McpSyncServerExchange.class);
        lenient().when(exchange.transportContext()).thenReturn(context);
        return exchange;
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
