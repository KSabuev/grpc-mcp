package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListMethodsTool implements McpTool {

    private final ConnectionProvider provider;
    private final McpJsonMapper mcpJsonMapper;

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("list_methods",
                        Map.of("type", "object", "properties", Map.of()))
                .description("""
                        Возвращает массив строк — полные имена gRPC методов в формате "package.ServiceName/methodName".
                        Методы обнаруживаются для текущего подключения (headers GRPC-Host/GRPC-Port в конфиге
                        MCP-клиента: через server reflection или proto-файл GRPC-ProtoFile).
                        Включает unary и streaming методы.
                        Каждое имя можно передать в get_method_schema и invoke_method.
                        Пример элемента: "com.example.MyService/myMethod".
                        Используй полное имя как есть, не обрезай пакетный префикс.
                        Если GRPC-ProtoFile — путь на машине клиента: при первом использовании
                        прочитай этот файл и отправь его содержимое через load_proto_file
                        (файл закэшируется на сервере по host:port + имя файла), затем повтори
                        list_methods; в следующих сессиях методы выдаются сразу из кэша.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        List<String> names = provider.methods(requireReflectionOrTarget(exchange)).stream()
                                .map(meta -> meta.fullMethodName())
                                .toList();
                        String json = mcpJsonMapper.writeValueAsString(names);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (Exception e) {
                        log.error("Error listing methods: {}", e.getMessage(), e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    static GrpcConnection requireReflectionOrTarget(McpSyncServerExchange exchange) {
        GrpcConnection connection = McpTool.connection(exchange);
        if (connection == null) {
            throw new IllegalArgumentException(GrpcConnection.NOT_CONFIGURED_MESSAGE);
        }
        if (!connection.hasTarget() && connection.usesReflection()) {
            throw new IllegalArgumentException(GrpcConnection.NO_TARGET_MESSAGE);
        }
        return connection;
    }
}
