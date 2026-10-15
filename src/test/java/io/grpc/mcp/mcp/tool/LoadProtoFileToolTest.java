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
import io.grpc.mcp.config.ProtoCacheConfig;
import io.grpc.mcp.connection.ConnectionProvider;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.grpc.channel.GrpcChannelFactory;
import io.grpc.mcp.grpc.compilation.ProtoCompilationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


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
        provider = new ConnectionProvider(channelFactory, props, new ProtoCompilationService(props),
                ProtoCacheConfig.nativeCache(ProtoCacheConfig.cacheManager(props)));
        tool = new LoadProtoFileTool(provider, props, new ObjectMapper());
    }

    private GrpcConnection clientConnection() {
        return new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "/home/user/nsi.proto");
    }

    private McpSchema.CallToolResult call(java.util.Map<String, Object> args) {
        var exchange = ListMethodsToolTest.exchange(clientConnection());
        return tool.build().callHandler().apply(exchange, new McpSchema.CallToolRequest("load_proto_file", args));
    }

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void compilesProtoIntoServerCache() throws Exception {
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
        assertThat(json.get("cached").asBoolean()).isFalse();
        assertThat(json.has("ref")).isFalse();
        assertThat(json.has("opencodeConfig")).isFalse();

        assertThat(provider.hasCachedProto(clientConnection())).isTrue();
        var methods = provider.methods(clientConnection());
        assertThat(methods).hasSize(1);
    }

    @Test
    void reload_sameContent_returnsCached() throws Exception {
        // Arrange
        call(java.util.Map.of("content", PROTO, "name", "nsi.proto"));

        // Act
        var result = call(java.util.Map.of("content", PROTO, "name", "nsi.proto"));

        // Assert
        assertThat(result.isError()).isFalse();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("cached").asBoolean()).isTrue();
        assertThat(json.get("methods").asInt()).isEqualTo(1);
    }

    @Test
    void cacheSharedAcrossSessions_sameConnection() throws Exception {
        // Arrange
        call(java.util.Map.of("content", PROTO, "name", "nsi.proto"));

        // Act
        var methods = provider.methods(clientConnection());

        // Assert
        assertThat(methods).hasSize(1);
    }

    @Test
    void reflectionConnection_returnsError() {
        // Arrange
        var exchange = ListMethodsToolTest.exchange(ListMethodsToolTest.connection());
        var handler = tool.build().callHandler();

        // Act
        var result = handler.apply(exchange, new McpSchema.CallToolRequest("load_proto_file", java.util.Map.of("content", PROTO)));

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("GRPC-ProtoFile");
    }

    @Test
    void loadedProto_usableBySessionTools() {
        // Arrange
        call(java.util.Map.of("content", PROTO, "name", "nsi.proto"));

        // Act
        var meta = provider.method(clientConnection(), "test.TestService/DoIt");

        // Assert
        assertThat(meta.fullMethodName()).isEqualTo("test.TestService/DoIt");
    }

    @Test
    void reload_secondFile_addedToSameCacheEntry() {
        // Arrange
        call(java.util.Map.of("content", PROTO, "name", "svc.proto"));

        // Act
        call(java.util.Map.of("content", PROTO, "name", "svc2.proto"));

        // Assert
        assertThat(provider.hasCachedProto(clientConnection())).isTrue();
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
        assertThat(provider.hasCachedProto(clientConnection())).isTrue();
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
        assertThat(provider.hasCachedProto(clientConnection())).isTrue();
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
    void invalidProto_returnsStructuredError() throws Exception {
        // Arrange

        // Act
        var result = call(java.util.Map.of(
                "content", "syntax = \"proto3\";\nmessage Broken {",
                "name", "broken.proto"));

        // Assert
        assertThat(result.isError()).isTrue();
        var json = new ObjectMapper().readTree(text(result));
        assertThat(json.get("error").asText()).isEqualTo("protoc_compile_error");
        assertThat(json.get("name").asText()).isEqualTo("broken.proto");
        assertThat(json.get("file").asText()).isEqualTo("broken.proto");
        assertThat(json.get("line").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(json.get("protocOutput").asText()).isNotEmpty();
        assertThat(text(result)).contains("загрузить proto");
        assertThat(provider.hasCachedProto(clientConnection())).isFalse();
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

        // Assert: scratch dirs are transient, nothing persistent is written
        assertThat(leftoverScratchDirs()).isZero();
        assertThat(Files.exists(tempDir.resolve("uploads"))).isFalse();
        assertThat(Files.list(tempDir.resolve("protos")).count()).isZero();
    }

    private static long leftoverScratchDirs() throws java.io.IOException {
        return Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                .filter(p -> p.getFileName().toString().startsWith("grpc-mcp-load-"))
                .count();
    }
}
