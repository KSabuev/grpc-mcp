package io.grpc.mcp.mcp.tool;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.exceptions.MethodNotFoundException;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.channel.MetadataInterceptor;
import io.grpc.mcp.mcp.ChannelRecoveredEvent;
import io.grpc.mcp.mcp.GrpcMethodRegistry;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.grpc.mcp.mcp.JsonSchemaKeys.*;
import static io.grpc.mcp.mcp.JsonSchemaType.*;
import static io.grpc.mcp.mcp.tool.ToolArgs.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class InvokeMethodTool implements McpTool {

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            TYPE, OBJECT.getValue(),
            PROPERTIES, Map.of(
                    METHOD, Map.of(TYPE, STRING.getValue(), DESCRIPTION, METHOD_DESCRIPTION),
                    ARGUMENTS, Map.of(
                            ONE_OF, List.of(
                                    Map.of(TYPE, OBJECT.getValue(), DESCRIPTION, "Одно сообщение (unary, server-streaming)"),
                                    Map.of(TYPE, ARRAY.getValue(), ITEMS, Map.of(TYPE, OBJECT.getValue()),
                                            DESCRIPTION, "Массив сообщений (client-streaming)"),
                                    Map.of(TYPE, STRING.getValue(),
                                            DESCRIPTION, "JSON-строка с объектом или массивом")),
                            DESCRIPTION, "Аргументы gRPC метода."),
                    METADATA, Map.of(
                            TYPE, OBJECT.getValue(),
                            ADDITIONAL_PROPERTIES, Map.of(TYPE, STRING.getValue()),
                            DESCRIPTION,
                            "gRPC заголовки. Ключ — заголовок (string), значение — строка. Пример: Authorization=Bearer <JWT>"),
                    MAX_SERVER_MESSAGES, Map.of(
                            TYPE, INTEGER.getValue(),
                            DESCRIPTION,
                            "Опционально. Максимальное количество сообщений для server-streaming. При превышении ответ обрезается до этого количества. По умолчанию 100.")),
            REQUIRED, List.of(METHOD, ARGUMENTS)
    );

    private static final String TOOL_DESCRIPTION = """
            Вызывает gRPC метод с переданными аргументами.
             Протокол вызова:
              1. list_methods — получить точное имя метода
              2. get_method_schema(method) — получить example с аргументами
              3. invoke_method(method, arguments, metadata) — передать arguments и опциональные заголовки
            ВАЖНО:
            Используй example из get_method_schema — это правильный источник
            для имён и типов полей для вызываемого метода.
            Параметры:
              - method: полное имя метода из list_methods
              - arguments: JSON-объект (unary, server-streaming) или массив объектов (client-streaming).
                Используйте example из get_method_schema как шаблон (формат и названия полей),
                но значения замените на реальные данные.
                Для client-streaming передайте массив объектов.
              - Опционально:
                metadata: JSON-объект {key: value} — gRPC заголовки, передаваемые в вызове.
                Ключ — название заголовка, значение — строка. 
                Пример: {"Authorization": "Bearer <JWT>", "X-Id": "123"}.
            Поддерживаются unary, server-streaming, client-streaming.
            Bidi-streaming не поддерживается.
            Server-streaming возвращает JSON-массив ответов (макс 100 сообщений, можно увеличить через maxServerMessages).
            Client-streaming возвращает один JSON-объект.
            Unary возвращает один JSON-объект.
            При ошибке возвращает isError=true с описанием.
            """;

    private static final String TIMESTAMP_HINT = """
            . Проверь формат полей через get_method_schema.
            Для Timestamp используйте формат: {"date": "2024-01-01T00:00:00Z", "timeZone": "UTC"}.""";

    static final int DEFAULT_MAX_SERVER_MESSAGES = 100;

    private static final JsonFormat.Parser JSON_PARSER = JsonFormat.parser().ignoringUnknownFields();
    private static final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer()
            .alwaysPrintFieldsWithNoPresence()
            .preservingProtoFieldNames();

    private final GrpcMethodRegistry registry;
    private final GrpcChannelManager channelManager;
    private final GrpcProperties properties;
    private final RetryTemplate retryTemplate;
    private final McpJsonMapper mcpJsonMapper;

    private final Map<String, MethodDescriptor<DynamicMessage, DynamicMessage>> descriptorCache =
            new ConcurrentHashMap<>();



    @EventListener
    public void onChannelRecovered(ChannelRecoveredEvent event) {
        clearDescriptorCache();
    }

    public void clearDescriptorCache() {
        descriptorCache.clear();
        log.debug("Descriptor cache cleared");
    }

    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool tool = McpSchema.Tool.builder("invoke_method", INPUT_SCHEMA)
                .description(TOOL_DESCRIPTION)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(request))
                .build();
    }

    private McpSchema.CallToolResult invoke(McpSchema.CallToolRequest request) {
        try {
            String methodName = (String) request.arguments().get(METHOD);
            var meta = registry.getMethod(methodName);

            if (meta.clientStreaming() && meta.serverStreaming()) {
                return ToolResults.error("Метод '" + methodName + "' является bidi-streaming и не поддерживается invoke_method.");
            }

            List<DynamicMessage> requests = parseRequestMessages(
                    request.arguments().get(ARGUMENTS), meta.inputDescriptor());

            if (requests.isEmpty()) {
                return ToolResults.error("arguments не должен быть пустым");
            }

            MethodDescriptor<DynamicMessage, DynamicMessage> md = getOrCreateDescriptor(meta);
            Map<String, String> callMetadata = extractMetadata(request);

            int maxMessages = DEFAULT_MAX_SERVER_MESSAGES;
            Object rawMax = request.arguments().get(MAX_SERVER_MESSAGES);
            if (rawMax instanceof Number n && n.intValue() > 0) {
                maxMessages = n.intValue();
            }

            return switch (methodType(meta)) {
                case CLIENT_STREAMING -> handleClientStreaming(md, requests, callMetadata);
                case SERVER_STREAMING -> handleServerStreaming(md, requests.getFirst(), callMetadata, maxMessages);
                default -> handleUnary(md, requests.getFirst(), callMetadata);
            };
        } catch (MethodNotFoundException e) {
            log.error("Method not found: {}", e.getMessage());
            return ToolResults.error(e.getMessage());
        } catch (StatusRuntimeException e) {
            log.error("gRPC call failed after retries: {} {}", e.getStatus().getCode(), e.getMessage(), e);
            return ToolResults.error("Ошибка gRPC вызова: " + e.getStatus().getCode() + ": " + e.getMessage());
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            log.error("Error parsing arguments: {}", e.getMessage(), e);
            return ToolResults.error("Ошибка парсинга аргументов: " + e.getMessage() + TIMESTAMP_HINT);
        } catch (Exception e) {
            log.error("Error invoking gRPC method: {}", e.getMessage(), e);
            return ToolResults.error("Ошибка вызова метода: " + e.getMessage());
        }
    }

    private List<DynamicMessage> parseRequestMessages(@NonNull Object raw, Descriptors.Descriptor protoDesc) {
        List<Map<String, Object>> maps = extractMaps(raw);
        if (maps.isEmpty()) {
            return List.of();
        }

        List<DynamicMessage> result = new ArrayList<>(maps.size());
        for (Map<String, Object> map : maps) {
            try {
                DynamicMessage.Builder builder = DynamicMessage.newBuilder(protoDesc);
                String json = mcpJsonMapper.writeValueAsString(map);
                JSON_PARSER.merge(json, builder);
                result.add(builder.build());
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to serialize message: " + e.getMessage(), e);
            }
        }
        return result;
    }

    private List<Map<String, Object>> extractMaps(@NonNull Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> maps = new ArrayList<>(list.size());
            for (Object e : list) {
                maps.add(normalizeMap(e));
            }
            return maps;
        }
        if (raw instanceof String s) {
            try {
                Object parsed = mcpJsonMapper.readValue(s, Object.class);
                if (parsed instanceof List<?> list) {
                    List<Map<String, Object>> maps = new ArrayList<>(list.size());
                    for (Object e : list) {
                        maps.add(normalizeMap(e));
                    }
                    return maps;
                }
                return List.of(normalizeMap(parsed));
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to parse arguments JSON: " + e.getMessage(), e);
            }
        }
        return List.of(normalizeMap(raw));
    }

    private Map<String, Object> normalizeMap(@NonNull Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> normalized = new HashMap<>(m.size());
            for (var e : m.entrySet()) {
                normalized.put(String.valueOf(e.getKey()), e.getValue());
            }
            return normalized;
        }
        throw new IllegalArgumentException(
                "arguments element must be a JSON object, got " + raw.getClass().getSimpleName());
    }

    private Map<String, String> extractMetadata(McpSchema.CallToolRequest request) {
        Object raw = request.arguments().get(METADATA);
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return Map.of();
            }
            Map<String, String> result = new HashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object value = e.getValue();
                if (value != null) {
                    result.put(String.valueOf(e.getKey()), String.valueOf(value));
                }
            }
            return result.isEmpty() ? Map.of() : result;
        }
        return Map.of();
    }

    private Channel buildCallChannel(Map<String, String> metadata) {
        Channel base = channelManager.getChannel();
        if (metadata == null || metadata.isEmpty()) {
            return base;
        }

        Metadata extraHeaders = new Metadata();
        metadata.forEach((k, v) -> {
            Metadata.Key<String> key = Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER);
            extraHeaders.put(key, v);
        });
        log.debug("Adding {} per-call metadata headers: {}", metadata.size(), metadata.keySet());

        return ClientInterceptors.intercept(base, new MetadataInterceptor(extraHeaders));
    }

    private CallOptions buildCallOptions() {
        return CallOptions.DEFAULT.withDeadlineAfter(
                properties.getCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private static MethodDescriptor.MethodType methodType(io.grpc.mcp.grpc.discovery.MethodMeta meta) {
        if (meta.clientStreaming()) return MethodDescriptor.MethodType.CLIENT_STREAMING;
        if (meta.serverStreaming()) return MethodDescriptor.MethodType.SERVER_STREAMING;
        return MethodDescriptor.MethodType.UNARY;
    }

    private MethodDescriptor<DynamicMessage, DynamicMessage> getOrCreateDescriptor(
            io.grpc.mcp.grpc.discovery.MethodMeta meta) {
        return descriptorCache.computeIfAbsent(meta.fullMethodName(), k ->
                MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                        .setType(methodType(meta))
                        .setFullMethodName(meta.fullMethodName())
                        .setRequestMarshaller(ProtoUtils.marshaller(
                                DynamicMessage.getDefaultInstance(meta.inputDescriptor())))
                        .setResponseMarshaller(ProtoUtils.marshaller(
                                DynamicMessage.getDefaultInstance(meta.outputDescriptor())))
                        .build()
        );
    }

    private String serializeMessage(DynamicMessage msg) throws IOException {
        return JSON_PRINTER.print(msg);
    }

    private String serializeMessageList(List<DynamicMessage> msgs) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>(msgs.size());
        for (DynamicMessage msg : msgs) {
            items.add(mcpJsonMapper.readValue(JSON_PRINTER.print(msg), new TypeRef<Map<String, Object>>() {}));
        }
        return mcpJsonMapper.writeValueAsString(items);
    }

    private McpSchema.CallToolResult handleClientStreaming(
            MethodDescriptor<DynamicMessage, DynamicMessage> md,
            List<DynamicMessage> requests,
            Map<String, String> metadata) throws Exception {

        Channel callChannel = buildCallChannel(metadata);
        CallOptions opts = buildCallOptions();

        DynamicMessage response = retryTemplate.execute(context -> {
            var call = ClientCalls.blockingClientStreamingCall(callChannel, md, opts);
            for (DynamicMessage msg : requests) {
                call.write(msg);
            }
            call.halfClose();
            try {
                return call.read();
            } catch (io.grpc.StatusException se) {
                throw se.getStatus().asRuntimeException();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Client-streaming call interrupted", ie);
            }
        });
        return ToolResults.success(serializeMessage(response));
    }

    private McpSchema.CallToolResult handleServerStreaming(
            MethodDescriptor<DynamicMessage, DynamicMessage> md,
            DynamicMessage request,
            Map<String, String> metadata,
            int maxMessages) throws Exception {

        Channel callChannel = buildCallChannel(metadata);
        CallOptions opts = buildCallOptions();

        List<DynamicMessage> responses = retryTemplate.execute(context -> {
            Iterator<DynamicMessage> it = ClientCalls.blockingServerStreamingCall(
                    callChannel, md, opts, request);
            List<DynamicMessage> list = new ArrayList<>(maxMessages);
            while (it.hasNext() && list.size() < maxMessages) {
                list.add(it.next());
            }
            if (it.hasNext()) {
                log.warn("Server-streaming {}: collected {} messages, more available. Truncating.",
                        md.getFullMethodName(), maxMessages);
            }
            return list;
        });
        return ToolResults.success(serializeMessageList(responses));
    }

    private McpSchema.CallToolResult handleUnary(
            MethodDescriptor<DynamicMessage, DynamicMessage> md,
            DynamicMessage request,
            Map<String, String> metadata) throws Exception {

        Channel callChannel = buildCallChannel(metadata);
        CallOptions opts = buildCallOptions();

        DynamicMessage response = retryTemplate.execute(context ->
                ClientCalls.blockingUnaryCall(callChannel, md, opts, request));
        return ToolResults.success(serializeMessage(response));
    }

}