package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import io.grpc.mcp.mcp.GrpcChannelHealthIndicator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCheckToolTest {

    @Mock GrpcChannelHealthIndicator healthIndicator;
    @Mock ServiceDiscoverer discovery;

    private McpJsonMapper mapper;
    private HealthCheckTool tool;

    @BeforeEach
    void setUp() {
        mapper = McpJsonDefaults.getMapper();
        tool = new HealthCheckTool(healthIndicator, discovery, mapper);
    }

    @Test
    void healthy_channelUp() {
        var health = Health.up().withDetail("channelState", "READY").build();
        when(healthIndicator.health()).thenReturn(health);
        when(discovery.getServices()).thenReturn(List.of("Svc1"));
        when(discovery.getMethods()).thenReturn(List.of(
                mock(MethodMeta.class), mock(MethodMeta.class)));

        var handler = tool.build().callHandler();
        var result = handler.apply(null,
                new McpSchema.CallToolRequest("health", Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"healthy\":true");
    }

    @Test
    void unhealthy_channelDown() {
        var health = Health.down().withDetail("channelState", "TRANSIENT_FAILURE").build();
        when(healthIndicator.health()).thenReturn(health);
        when(discovery.getServices()).thenReturn(List.of());
        when(discovery.getMethods()).thenReturn(List.of());

        var handler = tool.build().callHandler();
        var result = handler.apply(null,
                new McpSchema.CallToolRequest("health", Map.of()));

        assertThat(text(result)).contains("\"healthy\":false")
                .contains("TRANSIENT_FAILURE");
    }

    @Test
    void healthIndicatorException_returnsError() {
        when(healthIndicator.health()).thenThrow(new RuntimeException("connection refused"));

        var handler = tool.build().callHandler();
        var result = handler.apply(null,
                new McpSchema.CallToolRequest("health", Map.of()));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("connection refused");
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
