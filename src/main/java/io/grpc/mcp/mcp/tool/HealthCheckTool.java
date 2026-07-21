package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import io.grpc.mcp.mcp.GrpcChannelHealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckTool implements McpTool {

    private final GrpcChannelHealthIndicator healthIndicator;
    private final ServiceDiscoverer discovery;
    private final McpJsonMapper mcpJsonMapper;

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("health", ToolArgs.EMPTY_INPUT_SCHEMA)
                .description("""
                        Проверить доступность gRPC сервера.
                        Возвращает: channelState (READY/IDLE/CONNECTING/TRANSIENT_FAILURE/SHUTDOWN),
                        services (количество gRPC сервисов), methods (количество методов).
                        Если сервер недоступен — автоматическое восстановление происходит в фоне.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Health health = healthIndicator.health();
                        return textResult(toMap(health));
                    } catch (Exception e) {
                        log.error("Health check failed: {}", e.getMessage(), e);
                        return ToolResults.error("Ошибка проверки соединения: " + e.getMessage());
                    }
                })
                .build();
    }

    private Map<String, Object> toMap(Health health) {
        Map<String, Object> result = new LinkedHashMap<>(health.getDetails());
        result.put("healthy", health.getStatus().equals(org.springframework.boot.actuate.health.Status.UP));
        result.put("services", discovery.getServices().size());
        result.put("methods", discovery.getMethods().size());
        return result;
    }

    private McpSchema.CallToolResult textResult(Map<String, Object> payload) throws java.io.IOException {
        return McpSchema.CallToolResult.builder()
                .addTextContent(mcpJsonMapper.writeValueAsString(payload))
                .build();
    }

}
