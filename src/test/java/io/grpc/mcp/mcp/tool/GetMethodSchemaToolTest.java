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
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.mcp.ExampleGenerator;
import io.grpc.mcp.mcp.ProtoSchemaConverter;
import io.grpc.mcp.mcp.WellKnownTypes;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetMethodSchemaToolTest {

    @Mock ConnectionProvider provider;

    private McpJsonMapper mapper;
    private GetMethodSchemaTool tool;
    private GrpcConnection connection;
    private Descriptors.Descriptor inputDesc;
    private Descriptors.Descriptor outputDesc;

    @BeforeEach
    void setUp() {
        var props = new GrpcProperties();
        props.setSchemaMaxDepth(8);
        props.setCallTimeout(Duration.ofSeconds(10));
        props.setBackoffInitial(Duration.ofMillis(500));
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
        tool = new GetMethodSchemaTool(provider, converter, exampleGen, mapper);
        connection = ListMethodsToolTest.connection();

        inputDesc = msg("Input",
                field("name", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
        outputDesc = msg("Output",
                field("result", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING));
    }

    @Test
    void returnsSchemaForExistingMethod() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/m1"))).thenReturn(meta("m1", false, false));

        // Act
        var result = call("pkg.Svc/m1");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("inputSchema").contains("outputSchema").contains("example");
    }

    @Test
    void schemaIncludesStreamingFlags() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/stream"))).thenReturn(meta("stream", true, false));

        // Act
        var result = call("pkg.Svc/stream");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"serverStreaming\":true");
    }

    @Test
    void methodNotFound_returnsError() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/unknown")))
                .thenThrow(new IllegalArgumentException(
                        "Метод \"pkg.Svc/unknown\" не найден."));

        // Act
        var result = call("pkg.Svc/unknown");

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("не найден");
    }

    @Test
    void schemaIncludesClientStreamingFlag() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/cs"))).thenReturn(meta("cs", false, true));

        // Act
        var result = call("pkg.Svc/cs");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"clientStreaming\":true");
    }

    @Test
    void schemaIncludesServiceDescription() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/svcDesc"))).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("svcDesc").fullMethodName("pkg.Svc/svcDesc")
                        .inputDescriptor(inputDesc).outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                         .serviceDescription("Service for testing")
                         .build());

        // Act
        var result = call("pkg.Svc/svcDesc");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Сервис: Service for testing");
    }

    @Test
    void schemaIncludesInputMessageDescription() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/msgDesc"))).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("msgDesc").fullMethodName("pkg.Svc/msgDesc")
                        .inputDescriptor(inputDesc).outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                         .inputMessageDescription("Input message description")
                         .build());

        // Act
        var result = call("pkg.Svc/msgDesc");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Входное сообщение: Input message description");
    }

    @Test
    void schemaIncludesMethodDescription() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/methodDesc"))).thenReturn(
                MethodMeta.builder()
                        .serviceName("pkg.Svc").methodName("methodDesc").fullMethodName("pkg.Svc/methodDesc")
                        .inputDescriptor(inputDesc).outputDescriptor(outputDesc)
                        .serverStreaming(false).clientStreaming(false)
                         .methodDescription("Method description")
                         .build());

        // Act
        var result = call("pkg.Svc/methodDesc");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("Method description");
    }

    @Test
    void schemaForMethodWithNullDescriptions() {
        // Arrange
        when(provider.method(anyString(), eq(connection), eq("pkg.Svc/nullDesc"))).thenReturn(meta("nullDesc", false, false));

        // Act
        var result = call("pkg.Svc/nullDesc");

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("pkg.Svc/nullDesc");
    }

    private MethodMeta meta(String name, boolean serverStreaming, boolean clientStreaming) {
        return MethodMeta.builder()
                .serviceName("pkg.Svc").methodName(name)
                .fullMethodName("pkg.Svc/" + name)
                .inputDescriptor(inputDesc)
                .outputDescriptor(outputDesc)
                .serverStreaming(serverStreaming).clientStreaming(clientStreaming)
                .build();
    }

    private McpSchema.CallToolResult call(String methodName) {
        var handler = tool.build().callHandler();
        return handler.apply(ListMethodsToolTest.exchange(connection),
                new McpSchema.CallToolRequest("get_method_schema", Map.of("method", methodName)));
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
