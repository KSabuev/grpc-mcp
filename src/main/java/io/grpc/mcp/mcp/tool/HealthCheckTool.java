package io.grpc.mcp.mcp.tool;

import io.grpc.ConnectivityState;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthCheckTool implements McpTool {

    private final ConnectionProvider provider;
    private final McpJsonMapper mcpJsonMapper;

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("health",
                        Map.of("type", "object", "properties", Map.of()))
                .description("""
                        Проверить доступность gRPC сервера текущего подключения
                        (headers GRPC-Host/GRPC-Port в конфиге MCP-клиента).
                        Возвращает: channelState (READY/IDLE/CONNECTING/TRANSIENT_FAILURE/SHUTDOWN),
                        services (количество gRPC сервисов), methods (количество методов).
                        Если сервер недоступен — канал пересоздаётся автоматически при следующем вызове.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        GrpcConnection connection = requireConnection(exchange);
                        ConnectivityState state = provider.channel(connection).getState(true);
                        int methods = provider.methods(McpTool.sessionId(exchange), connection).size();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("target", connection.target());
                        result.put("channelState", state.name());
                        result.put("healthy", state == ConnectivityState.READY);
                        result.put("methods", methods);
                        return textResult(result);
                    } catch (Exception e) {
                        log.error("Health check failed: {}", e.getMessage(), e);
                        return ToolResults.error("Ошибка проверки соединения: " + e.getMessage());
                    }
                })
                .build();
    }

    private GrpcConnection requireConnection(McpSyncServerExchange exchange) {
        GrpcConnection connection = McpTool.connection(exchange);
        if (connection == null) {
            throw new IllegalArgumentException("Подключение не настроено");
        }
        if (!connection.hasTarget()) {
            throw new IllegalArgumentException("""
                    GRPC_ADDRESS не задан (адрес gRPC по умолчанию не настроен).
                    Задайте GRPC-Host (+ GRPC-Port) в headers конфига MCP-клиента (opencode.json)
                    либо GRPC_ADDRESS в окружении контейнера.
                    """);
        }
        return connection;
    }

    private McpSchema.CallToolResult textResult(Map<String, Object> payload) throws java.io.IOException {
        return McpSchema.CallToolResult.builder()
                .addTextContent(mcpJsonMapper.writeValueAsString(payload))
                .build();
    }
}
