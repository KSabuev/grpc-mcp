package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.grpc.mcp.mcp.ExampleGenerator;
import io.grpc.mcp.mcp.ProtoSchemaConverter;
import io.grpc.mcp.connection.ConnectionProvider;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GetMethodSchemaTool implements McpTool {

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("method", Map.of(
                    "type", "string",
                    "description", "Полное имя метода из list_methods")),
            "required", List.of("method")
    );

    private final ConnectionProvider provider;
    private final ProtoSchemaConverter schemaConverter;
    private final ExampleGenerator exampleGenerator;
    private final McpJsonMapper mcpJsonMapper;

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("get_method_schema", INPUT_SCHEMA)
                .description("""
                        Принимает method — полное имя метода из list_methods.
                        Возвращает JSON с полями: description, inputSchema, outputSchema, example.
                        inputSchema — JSON Schema входных аргументов (типы, required, описания полей).
                        outputSchema — JSON Schema ответа сервера.
                        example — шаблон аргументов для invoke_method (формат полей, подставьте свои значения).
                        Если GRPC-ProtoFile — абсолютный путь (файл на машине клиента): сначала прочитай
                        этот файл и отправь его содержимое через load_proto_file, затем повтори вызов.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String methodName = (String) request.arguments().get("method");
                        var meta = provider.method(
                                ListMethodsTool.requireReflectionOrTarget(exchange), methodName);

                        Map<String, Object> inputSchema = schemaConverter.toJsonSchema(
                                meta.inputDescriptor(),
                                meta.fieldComments(),
                                meta.enumValueComments()
                        );

                        Map<String, Object> outputSchema = schemaConverter.toJsonSchema(
                                meta.outputDescriptor(),
                                Map.of(),
                                Map.of()
                        );

                        Map<String, Object> example = exampleGenerator.generateExample(meta.inputDescriptor());
                        example.put("metadata", Map.of());

                        StringBuilder desc = new StringBuilder();
                        if (meta.serviceDescription() != null && !meta.serviceDescription().isBlank()) {
                            desc.append("Сервис: ").append(meta.serviceDescription()).append(". ");
                        }
                        if (meta.methodDescription() != null && !meta.methodDescription().isBlank()) {
                            desc.append(meta.methodDescription()).append(". ");
                        }
                        if (meta.inputMessageDescription() != null && !meta.inputMessageDescription().isBlank()) {
                            desc.append("Входное сообщение: ").append(meta.inputMessageDescription()).append(". ");
                        }
                        if (desc.isEmpty()) {
                            desc.append("Вызов ").append(meta.serviceName()).append("/").append(meta.methodName());
                        }

                        Map<String, Object> result = new java.util.LinkedHashMap<>();
                        result.put("description", desc.toString().trim());
                        result.put("inputSchema", inputSchema);
                        result.put("outputSchema", outputSchema);
                        result.put("example", example);
                        result.put("serverStreaming", meta.serverStreaming());
                        result.put("clientStreaming", meta.clientStreaming());

                        String resultJson = mcpJsonMapper.writeValueAsString(result);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(resultJson)
                                .build();
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid method name: {}", e.getMessage());
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage())
                                .isError(true)
                                .build();
                    } catch (Exception e) {
                        log.error("Error getting schema: {}", e.getMessage(), e);
                        return McpSchema.CallToolResult.builder()
                                .addTextContent("Ошибка получения схемы для метода: " + e.getMessage()
                                        + ". Проверь имя метода через list_methods.")
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }
}
