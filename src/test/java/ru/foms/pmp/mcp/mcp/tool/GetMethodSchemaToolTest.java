package io.grpc.mcp.mcp.tool;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.mcp.ExampleGenerator;
import io.grpc.mcp.mcp.GrpcMethodRegistry;
import io.grpc.mcp.mcp.ProtoSchemaConverter;
import io.grpc.mcp.mcp.WellKnownTypes;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMethodSchemaToolTest {

    @Mock GrpcMethodRegistry registry;

    private McpJsonMapper mapper;
    private GetMethodSchemaTool tool;
    private Descriptors.Descriptor inputDesc;
    private Descriptors.Descriptor outputDesc;

    @BeforeEach
    void setUp() {
        var props = new GrpcProperties();
        props.setSchemaMaxDepth(8);
        props.setCallTimeout(Duration.ofSeconds(10));
        props.setBackoffInitial(Duration.ofMillis(500));
        props.setHealthCheckInterval(Duration.ofSeconds(30));
        props.setMaxRetries(2);
        props.setKeepaliveTime(Duration.ofSeconds(10));
        props.setKeepaliveTimeout(Duration.ofSeconds(5));
        props.setRetryBackoffMultiplier(2.0);
        props.setRetryMaxDelay(Duration.ofSeconds(3));
        props.setProtocTimeout(Duration.ofSeconds(10));
        props.setReflectionTimeout(Duration.ofSeconds(5));
        props.setAddress("localhost:8080");

        mapper = McpJsonDefaults.getMapper();
        var converter = new ProtoSchemaConverter(props, new WellKnownTypes());
        var exampleGen = new ExampleGenerator(props, new WellKnownTypes());
        tool = new GetMethodSchemaTool(registry, converter, exampleGen, mapper);

        inputDesc = msg("Input",
                field("name", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        outputDesc = msg("Output",
                field("result", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
    }

    @Test
    void returnsSchemaForExistingMethod() {
        when(registry.getMethod("pkg.Svc/m1")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("m1")
                        .fullMethodName("pkg.Svc/m1")
                        .inputDescriptor(inputDesc)
                        .outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/m1")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("inputSchema").contains("outputSchema").contains("example");
    }

    @Test
    void schemaIncludesStreamingFlags() {
        when(registry.getMethod("pkg.Svc/stream")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("stream")
                        .fullMethodName("pkg.Svc/stream")
                        .inputDescriptor(inputDesc)
                        .outputDescriptor(outputDesc)
                        .serverStreaming(true).clientStreaming(false)
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/stream")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"serverStreaming\":true");
    }

    @Test
    void methodNotFound_returnsError() {
        when(registry.getMethod("pkg.Svc/unknown"))
                .thenThrow(new IllegalArgumentException(
                        "Метод \"pkg.Svc/unknown\" не найден."));

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/unknown")));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("не найден");
    }

    @Test
    void schemaIncludesClientStreamingFlag() {
        when(registry.getMethod("pkg.Svc/cs")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("cs")
                        .fullMethodName("pkg.Svc/cs")
                        .inputDescriptor(inputDesc)
                        .outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(true)
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/cs")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"clientStreaming\":true");
    }

    // === 2) Данные есть, все описания ===

    @Test
    void schemaIncludesServiceDescription() {
        when(registry.getMethod("pkg.Svc/svcDesc")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("svcDesc")
                        .fullMethodName("pkg.Svc/svcDesc")
                        .inputDescriptor(inputDesc).outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                        .serviceDescription("Service for testing")
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/svcDesc")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Сервис: Service for testing");
    }

    @Test
    void schemaIncludesInputMessageDescription() {
        when(registry.getMethod("pkg.Svc/msgDesc")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("msgDesc")
                        .fullMethodName("pkg.Svc/msgDesc")
                        .inputDescriptor(inputDesc).outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                        .inputMessageDescription("Input message description")
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/msgDesc")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Входное сообщение: Input message description");
    }

    @Test
    void schemaIncludesMethodDescription() {
        when(registry.getMethod("pkg.Svc/methodDesc")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("methodDesc")
                        .fullMethodName("pkg.Svc/methodDesc")
                        .inputDescriptor(inputDesc).outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                        .methodDescription("Method description")
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/methodDesc")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Method description");
    }

    // === 3) Данные отсутствуют ===

    @Test
    void schemaForMethodWithNullDescriptions() {
        when(registry.getMethod("pkg.Svc/nullDesc")).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("nullDesc")
                        .fullMethodName("pkg.Svc/nullDesc")
                        .inputDescriptor(inputDesc)
                        .outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                        .build());

        var handler = tool.build().callHandler();
        var result = handler.apply(null, new McpSchema.CallToolRequest("get_method_schema",
                Map.of("method", "pkg.Svc/nullDesc")));

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("pkg.Svc/nullDesc");
    }

    private static Descriptors.Descriptor msg(String name, DescriptorProtos.FieldDescriptorProto.Builder... fields) {
        var b = DescriptorProtos.DescriptorProto.newBuilder().setName(name);
        for (var f : fields) b.addField(f);
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("test").addMessageType(b).build();
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

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }
}
