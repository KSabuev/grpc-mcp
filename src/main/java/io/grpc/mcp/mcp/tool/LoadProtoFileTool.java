package io.grpc.mcp.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.connection.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoadProtoFileTool implements McpTool {

    private final ConnectionProvider provider;
    private final GrpcProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("load_proto_file",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "content", Map.of(
                                                "type", "string",
                                                "description",
                                                "Содержимое .proto файла (полный текст файла). " +
                                                        "Прочитайте файл локально и передайте его текст здесь."),
                                        "name", Map.of(
                                                "type", "string",
                                                "description",
                                                "Опционально. Человекочитаемое имя (например 'nsi.proto') — " +
                                                        "используется только в ответе, на работу не влияет.")),
                                "required", java.util.List.of("content")))
                .description("""
                        Сохраняет .proto-файл в ПАМЯТИ сервера grpc-mcp на время текущей
                        MCP-сессии и компилирует его (protoc), генерируя список gRPC-методов.
                        На диск сервера файл не записывается: после окончания сессии
                        загруженные данные удаляются. Менять конфиг (opencode.json) не нужно.

                        Используйте, когда на целевом gRPC сервере нет server reflection и
                        proto-файла на сервере (GRPC-ProtoFile с относительным именем),
                        а файл находится на машине клиента.

                        Как пользоваться:
                        1. Прочитайте .proto файл на машине клиента и передайте его
                           содержимое в content. Если в файле есть импорты — сначала
                           загрузите импортируемые файлы отдельными вызовами (порядок:
                           зависимости первыми, главное proto с service/rpc последним).
                           Файлы без service/rpc (чистые типы) сохраняются как
                           зависимости: methods=0 — это нормально.
                           Well-known типы (google/protobuf/*, google/type/*)
                           загружать не нужно — они уже на сервере.
                        2. Тулза сохранит файл в памяти сессии и скомпилирует его,
                           вернув количество обнаруженных методов.
                        3. Вызовите list_methods — методы сразу доступны для
                           текущего подключения.

                        Лимит размера файла — 1 МБ.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        String sessionId = McpTool.sessionId(exchange);
                        Object rawContent = request.arguments().get("content");
                        if (!(rawContent instanceof String content) || content.isBlank()) {
                            return ToolResults.error("content: передайте содержимое .proto файла строкой");
                        }
                        if (content.length() > properties.getMaxUploadSize()) {
                            return ToolResults.error("content: файл больше допустимого размера "
                                    + properties.getMaxUploadSize() + " символов");
                        }
                        if (!content.trim().startsWith("syntax")
                                && !content.contains("message ")
                                && !content.contains("service ")) {
                            log.warn("load_proto_file: content не похож на .proto файл, компилируем как есть");
                        }

                        Object rawName = request.arguments().get("name");
                        String name = rawName instanceof String s && !s.isBlank() ? s.trim() : "service.proto";

                        int methodCount = provider.loadProtoIntoSession(sessionId, content, name);

                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("name", name);
                        result.put("methods", methodCount);
                        if (methodCount == 0) {
                            result.put("hint", """
                                    В файле не найдено gRPC-методов (service/rpc): он сохранён
                                    как импортируемая зависимость в памяти сессии.
                                    Если это был главный файл — проверь его содержимое;
                                    если это зависимость — загрузи следующий файл,
                                    в конце главное proto с service.
                                    """);
                        } else {
                            result.put("hint", """
                                    Proto-файл загружен в память сессии (на диск не записан).
                                    Вызови list_methods — менять конфиг не нужно.
                                    """);
                        }
                        return ToolResults.success(objectMapper.writeValueAsString(result));
                    } catch (Exception e) {
                        log.error("Failed to load proto file into session: {}", e.getMessage(), e);
                        return ToolResults.error("Не удалось загрузить proto-файл в память сессии: " + e.getMessage());
                    }
                })
                .build();
    }
}
