package io.grpc.mcp.mcp.tool;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.mcp.GrpcMethodRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvokeMethodToolTest {

    @Mock GrpcMethodRegistry registry;
    @Mock GrpcChannelManager channelManager;
    @Mock RetryTemplate retryTemplate;

    private GrpcProperties properties;
    private McpJsonMapper mapper;
    private InvokeMethodTool tool;
    private BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler;
    private Descriptors.Descriptor inputDesc;
    private Descriptors.Descriptor outputDesc;

    @BeforeEach
    void setUp() {
        properties = new GrpcProperties();
        properties.setCallTimeout(Duration.ofSeconds(10));
        mapper = McpJsonDefaults.getMapper();
        tool = new InvokeMethodTool(registry, channelManager, properties, retryTemplate, mapper);
        handler = tool.build().callHandler();

        inputDesc = msg("Input",
                field("name", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        outputDesc = msg("Output",
                field("result", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
    }

    private DynamicMessage outputMsg(String value) {
        return DynamicMessage.newBuilder(outputDesc)
                .setField(outputDesc.findFieldByName("result"), value)
                .build();
    }

    private static McpSchema.CallToolRequest req(Map<String, Object> args) {
        return new McpSchema.CallToolRequest("invoke_method", args);
    }

    private MethodMeta.MethodMetaBuilder unaryMeta() {
        return MethodMeta.builder()
                .serviceName("test.TestService")
                .methodName("DoIt")
                .fullMethodName("test.TestService/DoIt")
                .inputDescriptor(inputDesc)
                .outputDescriptor(outputDesc)
                .serverStreaming(false)
                .clientStreaming(false);
    }

    private static Descriptors.Descriptor msg(String name, DescriptorProtos.FieldDescriptorProto.Builder... fields) {
        var b = DescriptorProtos.DescriptorProto.newBuilder().setName(name);
        for (var f : fields) b.addField(f);
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("test")
                .addMessageType(b).build();
        try {
            return Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[0])
                    .findMessageTypeByName(name);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder field(String name,
                                                                        DescriptorProtos.FieldDescriptorProto.Type type) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(1).setType(type);
    }

    // === 1) Корректные данные ===

    @Test
    void unarySuccess() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());
        doReturn(outputMsg("ok")).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", Map.of("name", "hello"))));

        assertThat(result.isError())
                .as("result content: " + text(result))
                .isFalse();
    }

    @Test
    void serverStreamingReturnsList() {
        when(registry.getMethod("test.TestService/Stream")).thenReturn(
                unaryMeta().serverStreaming(true).build());
        doReturn(List.of(outputMsg("a"), outputMsg("b"))).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/Stream",
                "arguments", Map.of("name", "x"))));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"result\"");
    }

    @Test
    void clientStreamingWithArray() {
        when(registry.getMethod("test.TestService/CS")).thenReturn(
                unaryMeta().clientStreaming(true).build());
        doReturn(outputMsg("merged")).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/CS",
                "arguments", List.of(Map.of("name", "a"), Map.of("name", "b")))));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("merged");
    }

    @Test
    void argumentsAsJsonString_parsedCorrectly() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());
        doReturn(outputMsg("parsed")).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", "{\"name\": \"test\"}")));

        assertThat(result.isError()).isFalse();
    }

    @Test
    void maxServerMessagesDoesNotAffectUnary() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());
        doReturn(outputMsg("ok")).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", Map.of("name", "h"),
                "maxServerMessages", 5)));

        assertThat(result.isError()).isFalse();
    }

    // === 2) Данные есть, ошибка ===

    @Test
    void grpcError_returnsIsError() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());
        doThrow(new StatusRuntimeException(Status.INVALID_ARGUMENT))
                .when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", Map.of("name", "hello"))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("INVALID_ARGUMENT");
    }

    @Test
    void bidiStreaming_returnsError() {
        when(registry.getMethod("test.TestService/Bidi")).thenReturn(
                unaryMeta().serverStreaming(true).clientStreaming(true).build());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/Bidi",
                "arguments", Map.of("name", "x"))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("bidi-streaming");
    }

    @Test
    void invalidJsonString_returnsError() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", "{bad json}")));

        assertThat(result.isError()).isTrue();
    }

    // === 3) Данные отсутствуют ===

    @Test
    void methodNotFound_returnsError() {
        when(registry.getMethod("test.TestService/DoIt"))
                .thenThrow(new IllegalArgumentException(
                        "Метод \"test.TestService/DoIt\" не найден."));

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", Map.of("name", "hello"))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("не найден");
    }

    @Test
    void emptyArgumentsList_returnsError() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", List.of())));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("не должен быть пустым");
    }

    @Test
    void zeroServerMessages_returnsEmptyList() {
        when(registry.getMethod("test.TestService/Stream")).thenReturn(
                unaryMeta().serverStreaming(true).build());
        doReturn(List.of()).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/Stream",
                "arguments", Map.of("name", "x"))));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).isEqualTo("[]");
    }

    @Test
    void grpcInvalidProtocolBuffer_returnsError() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());
        doThrow(new InvalidProtocolBufferException("test"))
                .when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", Map.of("name", "hello"))));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("парсинга аргументов");
    }

    @Test
    void extractMetadata_allNullValues_filtersOutToEmpty() {
        when(registry.getMethod("test.TestService/DoIt")).thenReturn(unaryMeta().build());
        doReturn(outputMsg("ok")).when(retryTemplate).execute(any());

        var metadata = new java.util.HashMap<String, Object>();
        metadata.put("X-Null-1", null);
        metadata.put("X-Null-2", null);

        var result = handler.apply(null, req(Map.of("method", "test.TestService/DoIt",
                "arguments", Map.of("name", "hello"),
                "metadata", metadata)));

        assertThat(result.isError()).isFalse();
    }

    @Test
    void maxServerMessages_isZero_usesDefault() {
        when(registry.getMethod("test.TestService/Stream")).thenReturn(
                unaryMeta().serverStreaming(true).build());
        doReturn(List.of(outputMsg("a"))).when(retryTemplate).execute(any());

        var result = handler.apply(null, req(Map.of("method", "test.TestService/Stream",
                "arguments", Map.of("name", "x"),
                "maxServerMessages", 0)));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"result\"");
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
