package io.grpc.mcp.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.grpc.channel.GrpcChannelFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadProtoFileToolTest {

    private static final String PROTO = """
            syntax = "proto3";
            package test;
            service TestService {
              rpc DoIt (Input) returns (Output);
            }
            message Input { string name = 1; }
            message Output { string result = 1; }
            """;

    @Mock GrpcChannelFactory channelFactory;

    @TempDir Path tempDir;

    private GrpcProperties props;
    private ConnectionProvider provider;
    private LoadProtoFileTool tool;

    @BeforeEach
    void setUp() {
        props = new GrpcProperties();
        props.setProtoBaseDir(tempDir.resolve("protos").toString());
        props.setProtocTimeout(java.time.Duration.ofSeconds(10));
        provider = new ConnectionProvider(channelFactory, props);
        tool = new LoadProtoFileTool(provider, props, new ObjectMapper());
    }

    private McpSchema.CallToolResult call(java.util.Map<String, Object> args) {
        var exchange = ListMethodsToolTest.exchange(ListMethodsToolTest.connection());
        return tool.build().callHandler().apply(exchange, new McpSchema.CallToolRequest("load_proto_file", args));
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void compilesProtoIntoSessionMemory() throws Exception {
        // Arrange

        // Act
        var result = call(java.util.Map.of(
                "content", PROTO,
                "name", "nsi.proto"));

        // Assert
        assertThat(result.isError()).isFalse();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("name").asText()).isEqualTo("nsi.proto");
        assertThat(json.get("methods").asInt()).isEqualTo(1);
        // no disk ref anymore: nothing to paste into the config
        assertThat(json.has("ref")).isFalse();
        assertThat(json.has("opencodeConfig")).isFalse();

        assertThat(provider.hasSessionProto("test-session")).isTrue();
        var methods = provider.methods("test-session",
                new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "/home/user/nsi.proto"));
        assertThat(methods).hasSize(1);
    }

    @Test
    void loadedProto_usableBySessionTools() {
        // Arrange
        call(java.util.Map.of("content", PROTO, "name", "svc.proto"));
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "/home/user/svc.proto");

        // Act
        var meta = provider.method("test-session", connection, "test.TestService/DoIt");

        // Assert
        assertThat(meta.fullMethodName()).isEqualTo("test.TestService/DoIt");
    }

    @Test
    void reload_replacesSessionProto() {
        // Arrange
        call(java.util.Map.of("content", PROTO, "name", "svc.proto"));

        // Act
        call(java.util.Map.of("content", PROTO, "name", "svc2.proto"));

        // Assert
        assertThat(provider.hasSessionProto("test-session")).isTrue();
    }

    @Test
    void protoWithoutService_savedAsDependencyNotError() throws Exception {
        // Arrange

        // Act
        var result = call(java.util.Map.of(
                "content", "syntax = \"proto3\";\npackage dep;\nmessage Msg { string id = 1; }\n",
                "name", "dep.proto"));

        // Assert
        assertThat(result.isError()).isFalse();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("methods").asInt()).isZero();
        // no session proto entry: the file is only an import dependency
        assertThat(provider.hasSessionProto("test-session")).isFalse();
        // but the file is kept in the session scratch dir for later imports
        Path scratch = Path.of(System.getProperty("java.io.tmpdir"), "grpc-mcp", "test-session");
        assertThat(Files.exists(scratch.resolve("dep.proto"))).isTrue();
    }

    @Test
    void mainProtoWithImport_compilesAfterDependencyUpload() throws Exception {
        // Arrange
        call(java.util.Map.of(
                "content", "syntax = \"proto3\";\npackage dep;\nmessage Msg { string id = 1; }\n",
                "name", "dep.proto"));

        // Act
        var result = call(java.util.Map.of(
                "content", """
                        syntax = "proto3";
                        package main;
                        import "dep.proto";
                        service Svc { rpc DoIt (dep.Msg) returns (dep.Msg); }
                        """,
                "name", "main.proto"));

        // Assert
        assertThat(result.isError()).isFalse();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("methods").asInt()).isEqualTo(1);
        assertThat(provider.hasSessionProto("test-session")).isTrue();
    }

    @Test
    void emptyContent_returnsError() {
        // Arrange

        // Act
        var result = call(java.util.Map.of("content", "  "));

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("content");
    }

    @Test
    void oversizedContent_returnsError() {
        // Arrange
        props.setMaxUploadSize(10);

        // Act
        var result = call(java.util.Map.of("content", "01234567890123456789"));

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("размера");
    }

    @Test
    void invalidProto_returnsError() {
        // Arrange

        // Act
        var result = call(java.util.Map.of(
                "content", "syntax = \"proto3\";\nmessage Broken {",
                "name", "broken.proto"));

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("загрузить proto");
    }

    @Test
    void defaultName_usedWhenNotProvided() throws Exception {
        // Arrange

        // Act
        var result = call(java.util.Map.of("content", PROTO));

        // Assert
        assertThat(result.isError()).isFalse();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("name").asText()).isEqualTo("service.proto");
    }

    @Test
    void contentNotLookingLikeProto_stillProcessed() throws Exception {
        // Arrange

        // Act
        var result = call(java.util.Map.of(
                "content", "// only a comment, no syntax line\n",
                "name", "weird.proto"));

        // Assert
        assertThat(result.isError()).isFalse();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("methods").asInt()).isZero();
    }

    @Test
    void noPersistentFile_created() throws Exception {
        // Arrange
        Files.createDirectories(tempDir.resolve("protos"));

        // Act
        call(java.util.Map.of("content", PROTO, "name", "nsi.proto"));

        // Assert
        // only the session scratch dir (tmp) holds a protoc temp file; nothing in the app dirs
        Path scratch = Path.of(System.getProperty("java.io.tmpdir"), "grpc-mcp", "test-session");
        assertThat(Files.list(scratch).count()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("uploads"))).isFalse();
        assertThat(Files.list(tempDir.resolve("protos")).count()).isZero();
    }
}
