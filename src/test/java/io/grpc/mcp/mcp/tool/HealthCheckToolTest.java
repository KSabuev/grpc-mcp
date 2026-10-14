package io.grpc.mcp.mcp.tool;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCheckToolTest {

    @Mock ConnectionProvider provider;
    @Mock ManagedChannel channel;

    private McpJsonMapper mapper;
    private HealthCheckTool tool;

    @BeforeEach
    void setUp() {
        mapper = McpJsonDefaults.getMapper();
        tool = new HealthCheckTool(provider, mapper);
    }

    @Test
    void healthy_channelReady() {
        // Arrange
        var connection = connection();
        when(provider.channel(connection)).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);
        when(provider.methods(anyString(), eq(connection))).thenReturn(List.of(mock(MethodMeta.class)));

        // Act
        var result = call(connection);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"healthy\":true").contains("READY");
    }

    @Test
    void unhealthy_channelTransientFailure() {
        // Arrange
        var connection = connection();
        when(provider.channel(connection)).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.TRANSIENT_FAILURE);
        when(provider.methods(anyString(), eq(connection))).thenReturn(List.of());

        // Act
        var result = call(connection);

        // Assert
        assertThat(text(result)).contains("\"healthy\":false").contains("TRANSIENT_FAILURE");
    }

    @Test
    void providerException_returnsError() {
        // Arrange
        var connection = connection();
        when(provider.channel(connection)).thenThrow(new RuntimeException("connection refused"));

        // Act
        var result = call(connection);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("connection refused");
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

    private McpSchema.CallToolResult call(GrpcConnection connection) {
        var handler = tool.build().callHandler();
        return handler.apply(ListMethodsToolTest.exchange(connection),
                new McpSchema.CallToolRequest("health", Map.of()));
    }

    private static GrpcConnection connection() {
        return new GrpcConnection("client-host:50051", "PLAINTEXT", Map.of(), null);
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
