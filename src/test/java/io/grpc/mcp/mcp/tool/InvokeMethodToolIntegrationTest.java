package io.grpc.mcp.mcp.tool;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.grpc.discovery.MethodMeta;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end invoke tests against a real in-process gRPC server: verifies the
 * actual ClientCalls paths (unary / server-streaming / client-streaming),
 * per-call metadata and argument serialization.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvokeMethodToolIntegrationTest {

    private static final String SVC = "test.TestService";

    private static Server server;
    private static String serverName;
    private static ManagedChannel channel;

    private final Map<String, MethodMeta> metas = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> capturedHeaders = new ConcurrentHashMap<>();

    private ConnectionProvider provider;
    private RetryTemplate retryTemplate;
    private InvokeMethodTool tool;
    private GrpcConnection connection;

    @BeforeAll
    void startServer() throws Exception {
        var input = msg("Input", field("name", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        var output = msg("Output", field("result", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));

        var unary = md(input, output, "DoIt", false, false);
        var sstream = md(input, output, "Stream", true, false);
        var cstream = md(input, output, "CS", false, true);

        ServerInterceptor headerCapture = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                for (String name : headers.keys()) {
                    var value = headers.get(io.grpc.Metadata.Key.of(name, io.grpc.Metadata.ASCII_STRING_MARSHALLER));
                    if (value != null) {
                        capturedHeaders.put(name, value);
                    }
                }
                return next.startCall(call, headers);
            }
        };

        serverName = "itest-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName)
                .addService(ServerInterceptors.intercept(
                        new io.grpc.BindableService() {
                            @Override
                            public io.grpc.ServerServiceDefinition bindService() {
                                return io.grpc.ServerServiceDefinition.builder("test.TestService")
                                        .addMethod(unary, ServerCalls.<DynamicMessage, DynamicMessage>asyncUnaryCall(
                                                (req, obs) -> {
                                                    obs.onNext(DynamicMessage.newBuilder(output)
                                                            .setField(output.findFieldByName("result"),
                                                                    "unary:" + str(req, "name")).build());
                                                    obs.onCompleted();
                                                }))
                                        .addMethod(sstream, ServerCalls.<DynamicMessage, DynamicMessage>asyncServerStreamingCall(
                                                (req, obs) -> {
                                                    obs.onNext(DynamicMessage.newBuilder(output)
                                                            .setField(output.findFieldByName("result"),
                                                                    "s:" + str(req, "name")).build());
                                                    obs.onNext(DynamicMessage.newBuilder(output)
                                                            .setField(output.findFieldByName("result"), "second").build());
                                                    obs.onCompleted();
                                                }))
                                        .addMethod(cstream, ServerCalls.<DynamicMessage, DynamicMessage>asyncClientStreamingCall(
                                                obs -> new io.grpc.stub.StreamObserver<>() {
                                                    final java.util.List<DynamicMessage> reqs = new java.util.ArrayList<>();

                                                    @Override
                                                    public void onNext(DynamicMessage value) {
                                                        reqs.add(value);
                                                    }

                                                    @Override
                                                    public void onError(Throwable t) {
                                                    }

                                                    @Override
                                                    public void onCompleted() {
                                                        StringBuilder sb = new StringBuilder();
                                                        reqs.forEach(r -> sb.append(str(r, "name")).append("+"));
                                                        obs.onNext(DynamicMessage.newBuilder(output)
                                                                .setField(output.findFieldByName("result"),
                                                                        "cs:" + sb).build());
                                                        obs.onCompleted();
                                                    }
                                                }))
                                        .build();
                            }
                        }, headerCapture))
                .build()
                .start();

        channel = InProcessChannelBuilder.forName(serverName).build();

        metas.put("test.TestService/DoIt", meta(input, output, "DoIt", false, false));
        metas.put("test.TestService/Stream", meta(input, output, "Stream", true, false));
        metas.put("test.TestService/CS", meta(input, output, "CS", false, true));
    }

    @AfterAll
    void stopServer() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        var properties = new GrpcProperties();
        properties.setCallTimeout(Duration.ofSeconds(10));
        provider = mock(ConnectionProvider.class);
        var retryFactory = mock(io.grpc.mcp.config.GrpcRetryTemplateFactory.class);
        retryTemplate = mock(RetryTemplate.class);
        lenient().when(retryFactory.forChannel(anyString())).thenReturn(retryTemplate);
        tool = new InvokeMethodTool(provider, retryFactory, properties, McpJsonDefaults.getMapper());
        connection = ListMethodsToolTest.connection();
        lenient().when(provider.channel(connection)).thenReturn(channel);
        // execute the callback for real (no retries)
        when(retryTemplate.execute(any(RetryCallback.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            RetryCallback<Object, RuntimeException> cb =
                    (RetryCallback<Object, RuntimeException>) inv.getArgument(0);
            return cb.doWithRetry(null);
        });
        capturedHeaders.clear();
    }

    private McpSchema.CallToolResult call(Map<String, Object> args) {
        var req = new McpSchema.CallToolRequest("invoke_method", args);
        return tool.build().callHandler().apply(ListMethodsToolTest.exchange(connection), req);
    }

    private void stub(String full) {
        when(provider.method(anyString(), eq(connection), eq(full))).thenReturn(metas.get(full));
    }

    private static String str(DynamicMessage m, String f) {
        return (String) m.getField(m.getDescriptorForType().findFieldByName(f));
    }

    @Test
    void unary_realCall_returnsResponse() {
        // Arrange
        stub("test.TestService/DoIt");

        // Act
        var result = call(Map.of("method", "test.TestService/DoIt", "arguments", Map.of("name", "hello")));

        // Assert
        assertThat(result.isError()).as("tool result: " + text(result)).isFalse();
        assertThat(text(result)).contains("unary:hello");
    }

    @Test
    void serverStreaming_realCall_returnsAllMessages() {
        // Arrange
        stub("test.TestService/Stream");

        // Act
        var result = call(Map.of("method", "test.TestService/Stream", "arguments", Map.of("name", "x")));

        // Assert
        assertThat(result.isError()).as("tool result: " + text(result)).isFalse();
        assertThat(text(result)).contains("s:x").contains("second");
    }

    @Test
    void serverStreaming_maxMessages_truncates() {
        // Arrange
        stub("test.TestService/Stream");

        // Act
        var result = call(Map.of("method", "test.TestService/Stream", "arguments", Map.of("name", "x"),
                "maxServerMessages", 1));

        // Assert
        assertThat(result.isError()).as("tool result: " + text(result)).isFalse();
        assertThat(text(result)).contains("s:x");
        assertThat(text(result)).doesNotContain("second");
    }

    @Test
    void clientStreaming_realCall_sendsAllRequests() {
        // Arrange
        stub("test.TestService/CS");

        // Act
        var result = call(Map.of("method", "test.TestService/CS",
                "arguments", List.of(Map.of("name", "a"), Map.of("name", "b"))));

        // Assert
        assertThat(result.isError()).as("tool result: " + text(result)).isFalse();
        assertThat(text(result)).contains("cs:a+b+");
    }

    @Test
    void clientStreaming_argumentsAsJsonStringList() {
        // Arrange
        stub("test.TestService/CS");

        // Act
        var result = call(Map.of("method", "test.TestService/CS",
                "arguments", "[{\"name\":\"a\"},{\"name\":\"b\"}]"));

        // Assert
        assertThat(result.isError()).as("tool result: " + text(result)).isFalse();
        assertThat(text(result)).contains("cs:a+b+");
    }

    @Test
    void perCallMetadata_sentToServer() {
        // Arrange
        stub("test.TestService/DoIt");

        // Act
        var result = call(Map.of("method", "test.TestService/DoIt", "arguments", Map.of("name", "x"),
                "metadata", Map.of("X-Test-Header", "v1")));

        // Assert
        assertThat(result.isError()).as("tool result: " + text(result)).isFalse();
        assertThat(capturedHeaders).containsEntry("x-test-header", "v1");
    }

    @Test
    void missingConnection_returnsError() {
        // Arrange
        var exchange = mock(io.modelcontextprotocol.server.McpSyncServerExchange.class);
        lenient().when(exchange.transportContext()).thenReturn(McpTransportContext.EMPTY);
        lenient().when(exchange.sessionId()).thenReturn("test-session");

        var req = new McpSchema.CallToolRequest("invoke_method",
                Map.of("method", "test.TestService/DoIt", "arguments", Map.of("name", "x")));

        // Act
        var result = tool.build().callHandler().apply(exchange, req);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Подключение не настроено");
    }

    @Test
    void unavailableStatus_retryExhausted_returnsError() {
        // Arrange
        stub("test.TestService/DoIt");
        when(retryTemplate.execute(any(RetryCallback.class)))
                .thenThrow(new io.grpc.StatusRuntimeException(Status.UNAVAILABLE));

        // Act
        var result = call(Map.of("method", "test.TestService/DoIt", "arguments", Map.of("name", "x")));

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("UNAVAILABLE");
    }

    private MethodMeta meta(Descriptors.Descriptor in, Descriptors.Descriptor out,
                            String name, boolean serverStreaming, boolean clientStreaming) {
        return MethodMeta.builder()
                .serviceName("test.TestService")
                .methodName(name)
                .fullMethodName("test.TestService/" + name)
                .inputDescriptor(in)
                .outputDescriptor(out)
                .serverStreaming(serverStreaming)
                .clientStreaming(clientStreaming)
                .build();
    }

    private io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> md(
            Descriptors.Descriptor in, Descriptors.Descriptor out,
            String name, boolean serverStreaming, boolean clientStreaming) {
        return io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(serverStreaming
                        ? io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING
                        : clientStreaming ? io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING
                                : io.grpc.MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("test.TestService/" + name)
                .setRequestMarshaller(dynamicMarshaller(in))
                .setResponseMarshaller(dynamicMarshaller(out))
                .build();
    }

    private static io.grpc.MethodDescriptor.Marshaller<DynamicMessage> dynamicMarshaller(
            Descriptors.Descriptor desc) {
        return new io.grpc.MethodDescriptor.Marshaller<>() {
            @Override
            public java.io.InputStream stream(DynamicMessage value) {
                return new java.io.ByteArrayInputStream(value.toByteArray());
            }

            @Override
            public DynamicMessage parse(java.io.InputStream input) {
                try {
                    return DynamicMessage.parseFrom(desc, input.readAllBytes());
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
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
        return DescriptorProtos.FieldDescriptorProto.newBuilder().setName(name).setNumber(1).setType(type);
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
