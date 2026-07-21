package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.mcp.GrpcMethodRegistry;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListMethodsToolTest {

    @Mock GrpcMethodRegistry registry;

    private McpJsonMapper mapper;
    private ListMethodsTool tool;

    @BeforeEach
    void setUp() {
        mapper = McpJsonDefaults.getMapper();
        tool = new ListMethodsTool(registry, mapper);
    }

    @Test
    void returnsMethodNames() {
        when(registry.getAllMethodNames()).thenReturn(List.of("pkg.Svc/m1", "pkg.Svc/m2"));

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("list_methods", Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("pkg.Svc/m1").contains("pkg.Svc/m2");
    }

    @Test
    void emptyRegistry_returnsEmptyList() {
        when(registry.getAllMethodNames()).thenReturn(List.of());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("list_methods", Map.of()));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("[]");
    }

    @Test
    void registryException_returnsError() {
        when(registry.getAllMethodNames()).thenThrow(new RuntimeException("DB error"));

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("list_methods", Map.of()));

        assertThat(result.isError()).isTrue();
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
