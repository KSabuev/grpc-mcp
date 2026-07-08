package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.grpc.mcp.mcp.GrpcMethodRegistry;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListMethodsTool implements McpTool {

    private final GrpcMethodRegistry registry;
    private final McpJsonMapper mcpJsonMapper;

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("list_methods",
                        Map.of("type", "object", "properties", Map.of()))
                .description("""
                        Возвращает массив строк — полные имена gRPC методов в формате "package.ServiceName/methodName".
                        Включает unary и streaming методы.
                        Каждое имя можно передать в get_method_schema и invoke_method.
                        Пример элемента: "com.example.MyService/myMethod".
                        Используйте полное имя как есть, не обрезайте пакетный префикс.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String json = mcpJsonMapper.writeValueAsString(registry.getAllMethodNames());
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json)
                                .build();
                    } catch (Exception e) {
                        log.error("Error listing methods: {}", e.getMessage(), e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getClass().getSimpleName() + ": " + e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }
}
