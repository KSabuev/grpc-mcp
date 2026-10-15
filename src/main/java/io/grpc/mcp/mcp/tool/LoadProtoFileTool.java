package io.grpc.mcp.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.grpc.discovery.ProtocCompilationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
                                                        "Прочитай файл локально и передай его текст здесь."),
                                        "name", Map.of(
                                                "type", "string",
                                                "description",
                                                "Опционально. Человекочитаемое имя (например 'nsi.proto') — " +
                                                        "используется как имя файла в кэше и в ответе.")),
                                "required", java.util.List.of("content")))
                .description("""
                        Сохраняет .proto-файл в кэше сервера grpc-mcp (только RAM, на диск
                        не записывается) и компилирует его (protoc), генерируя список
                        gRPC-методов. Кэшируется по ключу host:port + имя файла
                        (GRPC-Host/GRPC-Port + имя из GRPC-ProtoFile без пути) и переживает
                        MCP-сессии: в новой сессии list_methods/get_method_schema/invoke_method
                        работают сразу, без повторной загрузки. Повторная загрузка того же
                        содержимого отвечает cached=true без запуска protoc; изменённое
                        содержимое пересобирается. Кэш живёт до idle-таймаута (30 дн.),
                        переполнения (64 записи, LRU) или рестарта сервера. Менять конфиг
                        (opencode.json) не нужно.

                        Используй, когда на целевом gRPC сервере нет server reflection и
                        proto-файла на сервере (GRPC-ProtoFile с абсолютным/~/путём —
                        файл на машине клиента).

                        Как пользоваться:
                        1. Прочитай .proto файл на машине клиента и передай его
                           содержимое в content. Если в файле есть импорты — сначала
                           загрузи импортируемые файлы отдельными вызовами (порядок:
                           зависимости первыми, главное proto с service/rpc последним).
                           Файлы без service/rpc (чистые типы) сохраняются как
                           зависимости: methods=0 — это нормально.
                           Well-known типы (google/protobuf/*, google/type/*)
                           загружать не нужно — они уже на сервере.
                        2. Тулза сохранит файл в кэше сервера и скомпилирует его,
                           вернув количество обнаруженных методов и флаг cached.
                        3. Вызови list_methods — методы сразу доступны для
                           текущего подключения.

                        Лимит размера файла — 1 МБ.
                        """)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    GrpcConnection connection = null;
                    String name = null;
                    try {
                        Object rawContent = request.arguments().get("content");
                        if (!(rawContent instanceof String content) || content.isBlank()) {
                            return ToolResults.error("content: передай содержимое .proto файла строкой.");
                        }
                        if (content.length() > properties.getMaxUploadSize()) {
                            return ToolResults.error("content: файл больше допустимого размера "
                                    + properties.getMaxUploadSize() + " символов.");
                        }
                        if (!content.trim().startsWith("syntax")
                                && !content.contains("message ")
                                && !content.contains("service ")) {
                            log.warn("load_proto_file: content не похож на .proto файл, компилируем как есть");
                        }

                        Object rawName = request.arguments().get("name");
                        name = rawName instanceof String s && !s.isBlank() ? s.trim() : "service.proto";

                        connection = McpTool.connection(exchange);
                        if (connection == null || !connection.isClientPath()) {
                            return ToolResults.error("""
                                    load_proto_file работает только когда GRPC-ProtoFile — путь на машине клиента
                                    (абсолютный или ~/путь). У текущего подключения либо GRPC-ProtoFile не задан,
                                    либо указан файл на сервере/reflection — загружать proto не нужно.""");
                        }
                        var loadResult = provider.loadProto(connection, content, name);
                        int methodCount = loadResult.methodCount();
                        boolean cached = loadResult.cached();

                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("name", name);
                        response.put("methods", methodCount);
                        response.put("cached", cached);
                        if (methodCount == 0) {
                            response.put("hint", """
                                    В файле не найдено gRPC-методов (service/rpc): он сохранён
                                    как импортируемая зависимость в кэше сервера.
                                    Если это был главный файл — проверь его содержимое;
                                    если это зависимость — загрузи следующий файл,
                                    в конце главное proto с service.
                                    """);
                        } else if (cached) {
                            response.put("hint", """
                                    Содержимое файла уже было в кэше сервера — protoc не запускался.
                                    Вызови list_methods — менять конфиг не нужно.
                                    """);
                        } else {
                            response.put("hint", """
                                    Proto-файл скомпилирован и закэширован на сервере (только RAM,
                                    на диск не записан). Ключ кэша: host:port + имя файла.
                                    В следующих MCP-сессиях загрузка не потребуется.
                                    Вызови list_methods — менять конфиг не нужно.
                                    """);
                        }
                        return ToolResults.success(objectMapper.writeValueAsString(response));
                    } catch (ProtocCompilationException e) {
                        log.error("Proto compilation failed for '{}': {}", name, e.getMessage());
                        Map<String, Object> error = new LinkedHashMap<>();
                        error.put("error", "protoc_compile_error");
                        error.put("name", name);
                        if (e.getFile() != null) {
                            error.put("file", Path.of(e.getFile()).getFileName().toString());
                            error.put("line", e.getLine());
                            error.put("column", e.getColumn());
                        }
                        error.put("message", "Не удалось загрузить proto-файл: " + e.getMessage());
                        error.put("protocOutput", e.getRawOutput());
                        error.put("hint", """
                                Исправь синтаксис файла на машине клиента по указанной позиции
                                (путь из GRPC-ProtoFile: %s) и повтори load_proto_file.
                                Кэш сервера при ошибке не изменяется.
                                """.formatted(connection == null ? "не задан" : connection.protoRef()));
                        try {
                            return ToolResults.error(objectMapper.writeValueAsString(error));
                        } catch (Exception serializationError) {
                            return ToolResults.error("Не удалось загрузить proto-файл: " + e.getMessage());
                        }
                    } catch (Exception e) {
                        log.error("Failed to load proto file: {}", e.getMessage(), e);
                        return ToolResults.error("Не удалось загрузить proto-файл: " + e.getMessage());
                    }
                })
                .build();
    }
}
