package io.grpc.mcp.connection;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.channel.GrpcChannelFactory;
import io.grpc.mcp.grpc.discovery.MethodMeta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionProviderTest {

    private static final String PROTO = """
            syntax = "proto3";
            package test;
            service TestService {
              rpc DoIt (Input) returns (Output);
            }
            message Input { string name = 1; }
            message Output { string result = 1; }
            """;

    private static final String PROTO_TWO = """
            syntax = "proto3";
            package test;
            service TestService {
              rpc DoIt (Input) returns (Output);
              rpc Other (Input) returns (Output);
            }
            message Input { string name = 1; }
            message Output { string result = 1; }
            """;

    @Mock GrpcChannelFactory channelFactory;
    @Mock ManagedChannel channel;

    @TempDir Path tempDir;

    private GrpcProperties props;
    private ConnectionProvider provider;

    @BeforeEach
    void setUp() {
        props = new GrpcProperties();
        props.setProtoBaseDir(tempDir.resolve("protos").toString());
        props.setConnectionIdleTimeout(Duration.ofMinutes(30));
        props.setSessionProtoIdleTimeout(Duration.ofHours(1));
        props.setProtocTimeout(Duration.ofSeconds(10));
        props.setReflectionTimeout(Duration.ofSeconds(5));
        provider = new ConnectionProvider(channelFactory, props);
    }

    private GrpcConnection connection() {
        return new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), null);
    }

    private GrpcConnection clientPathConnection(String path) {
        return new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), path);
    }

    private void writeBaseProto(String name) throws IOException {
        Files.createDirectories(Path.of(props.getProtoBaseDir()));
        Files.writeString(Path.of(props.getProtoBaseDir(), name), PROTO);
    }

    @Test
    void channel_createdOnceAndCached() {
        // Arrange
        when(channelFactory.createChannel(any())).thenReturn(channel);
        var c = provider.channel(connection());

        // Act
        var c2 = provider.channel(connection());

        // Assert
        assertThat(c).isSameAs(c2);
        verify(channelFactory, times(1)).createChannel(any());
    }

    @Test
    void channel_missingTarget_throws() {
        // Arrange
        var noTarget = new GrpcConnection("", "PLAINTEXT", Map.of(), null);

        // Act
        var thrown = catchThrowable(() -> provider.channel(noTarget));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GRPC-Host");
    }

    @Test
    void evictChannel_removesAndCloses() {
        // Arrange
        when(channelFactory.createChannel(any())).thenReturn(channel);
        var c = connection();
        provider.channel(c);

        // Act
        provider.evictChannel(c.channelKey());

        // Assert
        verify(channel).shutdownNow();
        assertThat(provider.isEmpty()).isTrue();
    }

    @Test
    void resolveProtoPath_regularFile_inBaseDir() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");

        // Act
        var path = provider.resolveProtoPath("svc.proto");

        // Assert
        assertThat(path).isEqualTo(Path.of(props.getProtoBaseDir(), "svc.proto"));
    }

    @Test
    void resolveProtoPath_traversalRejected() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("../secret.proto"));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void resolveProtoPath_absolutePathRejected() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("/etc/passwd"));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absolute");
    }

    @Test
    void resolveProtoPath_legacyUploadRef_throws() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("upload/abc.proto"));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load_proto_file");
    }

    @Test
    void resolveProtoPath_missingFile_throws() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("nope.proto"));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void discoveryFileBased_realProtoFile() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");

        // Act
        var methods = provider.methods("s1", connection);

        // Assert
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
    }

    @Test
    void discoveryResult_cached() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");
        provider.methods("s1", connection);

        // Act
        var cached = provider.methods("s1", connection);

        // Assert
        assertThat(cached).isNotNull();
    }

    @Test
    void method_lookupByFullName() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");

        // Act
        var meta = provider.method("s1", connection, "test.TestService/DoIt");

        // Assert
        assertThat(meta.serviceName()).isEqualTo("test.TestService");
    }

    @Test
    void method_unknown_throws() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");

        // Act
        var thrown = catchThrowable(() -> provider.method("s1", connection, "test.TestService/Nope"));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    void discoveryKey_fileBased_dependsOnProtoRef() throws IOException {
        // Arrange
        Files.createDirectories(Path.of(props.getProtoBaseDir()));
        Files.writeString(Path.of(props.getProtoBaseDir(), "a.proto"), "syntax = \"proto3\";");
        Files.writeString(Path.of(props.getProtoBaseDir(), "b.proto"), "syntax = \"proto3\";");

        var c1 = new GrpcConnection("h:1", "PLAINTEXT", Map.of(), "a.proto");
        var c2 = new GrpcConnection("h:1", "PLAINTEXT", Map.of(), "b.proto");

        // Act
        var keyA = provider.discoveryKey("s1", c1);

        // Assert
        assertThat(keyA).isNotEqualTo(provider.discoveryKey("s1", c2));
    }

    @Test
    void discoveryKey_clientPath_dependsOnSession() {
        // Arrange
        var conn = clientPathConnection("/home/user/svc.proto");

        // Act
        var keyS1 = provider.discoveryKey("s1", conn);

        // Assert
        assertThat(keyS1).isNotEqualTo(provider.discoveryKey("s2", conn));
    }

    @Test
    void shutdown_closesAllChannels() {
        // Arrange
        when(channelFactory.createChannel(any())).thenReturn(channel);
        provider.channel(connection());

        // Act
        provider.shutdown();

        // Assert
        verify(channel).shutdownNow();
        assertThat(provider.isEmpty()).isTrue();
    }

    // === In-memory session protos ===

    @Test
    void clientPath_withoutLoadedProto_throwsLlmRequest() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");

        // Act
        var thrown = catchThrowable(() -> provider.methods("s1", connection));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/home/user/svc.proto")
                .hasMessageContaining("load_proto_file")
                .hasMessageContaining("отсутствует по пути");
    }

    @Test
    void loadProtoIntoSession_methodsAvailableForSession() {
        // Arrange
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");

        // Act
        var methods = provider.methods("s1", clientPathConnection("/home/user/svc.proto"));

        // Assert
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
    }

    @Test
    void loadProtoIntoSession_methodLookup() {
        // Arrange
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");

        // Act
        var meta = provider.method("s1", clientPathConnection("/home/user/svc.proto"), "test.TestService/DoIt");

        // Assert
        assertThat(meta.serviceName()).isEqualTo("test.TestService");
    }

    @Test
    void loadProtoIntoSession_replacesPrevious() {
        // Arrange
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");

        // Act
        provider.loadProtoIntoSession("s1", PROTO_TWO, "svc.proto");

        // Assert
        var methods = provider.methods("s1", clientPathConnection("/home/user/svc.proto"));
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt", "test.TestService/Other");
    }

    @Test
    void sessionIsolation_protoNotVisibleToOtherSessions() {
        // Arrange
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");
        var otherSession = clientPathConnection("/home/user/svc.proto");

        // Act
        var thrown = catchThrowable(() -> provider.methods("s2", otherSession));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load_proto_file");
        assertThat(provider.hasSessionProto("s2")).isFalse();
    }

    @Test
    void loadProtoIntoSession_noMethods_keptAsDependency() {
        // Arrange

        // Act
        int methods = provider.loadProtoIntoSession("s1", "syntax = \"proto3\";", "empty.proto");

        // Assert
        assertThat(methods).isZero();
        assertThat(provider.hasSessionProto("s1")).isFalse();
    }

    @Test
    void nothingStoredOnServerDisk() throws IOException {
        // Arrange
        Files.createDirectories(Path.of(props.getProtoBaseDir()));

        // Act
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");

        // Assert
        // proto base dir stays empty, no uploads dir is created anywhere
        assertThat(Files.list(Path.of(props.getProtoBaseDir()))).isEmpty();
        assertThat(Files.exists(Path.of(props.getProtoBaseDir()).getParent().resolve("uploads"))).isFalse();
        // only scratch tmp dir for protoc exists (session-scoped)
        assertThat(Files.isDirectory(provider.sessionTempDir("s1"))).isTrue();
    }

    @Test
    void idleSessionProto_evictedBySweep() throws InterruptedException {
        // Arrange
        props.setSessionProtoIdleTimeout(Duration.ofMillis(1));
        provider.loadProtoIntoSession("s-idle", PROTO, "svc.proto");
        Path tempDir = provider.sessionTempDir("s-idle");
        assertThat(Files.isDirectory(tempDir)).isTrue();
        Thread.sleep(50);

        // Act
        provider.sweep();

        // Assert
        assertThat(provider.hasSessionProto("s-idle")).isFalse();
        assertThat(Files.exists(tempDir)).isFalse();
    }

    @Test
    void activeSessionProto_notEvicted() {
        // Arrange
        provider.loadProtoIntoSession("s-active", PROTO, "svc.proto");

        // Act
        provider.sweep();

        // Assert
        assertThat(provider.hasSessionProto("s-active")).isTrue();
        assertThat(Files.isDirectory(provider.sessionTempDir("s-active"))).isTrue();
    }

    @Test
    void shutdown_clearsSessionProtos() {
        // Arrange
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");
        Path tempDir = provider.sessionTempDir("s1");

        // Act
        provider.shutdown();

        // Assert
        assertThat(provider.hasSessionProto("s1")).isFalse();
        assertThat(Files.exists(tempDir)).isFalse();
    }

    // === init / wkt / sweep ===

    @Test
    void init_createsEmptyWktDir() throws IOException {
        // Arrange
        props.setWktDir(tempDir.resolve("wkt-empty").toString());

        // Act
        provider.init();

        // Assert
        assertThat(Files.isDirectory(tempDir.resolve("wkt-empty"))).isTrue();
        assertThat(Files.list(tempDir.resolve("wkt-empty")).count()).isZero();
    }

    @Test
    void loadProtoIntoSession_missingWktImport_extractedAndCompiled() throws Exception {
        // Arrange
        props.setWktDir(tempDir.resolve("wkt-lazy").toString());
        var proto = """
                syntax = "proto3";
                package main;
                import "google/api/annotations.proto";
                service Svc {
                  rpc DoIt (Msg) returns (Msg) {
                    option (google.api.http) = { get: "/v1/it" };
                  }
                }
                message Msg { string id = 1; }
                """;

        // Act
        var methodCount = provider.loadProtoIntoSession("s-wkt", proto, "main.proto");

        // Assert
        assertThat(methodCount).isEqualTo(1);
        assertThat(provider.methods("s-wkt", clientPathConnection("/home/user/main.proto")))
                .extracting(MethodMeta::fullMethodName)
                .contains("main.Svc/DoIt");
        assertThat(Files.exists(tempDir.resolve("wkt-lazy/google/api/annotations.proto"))).isTrue();
    }

    @Test
    void shutdown_afterInit_stopsSweeperAndClearsAll() {
        // Arrange
        provider.init();
        when(channelFactory.createChannel(any())).thenReturn(channel);
        provider.channel(connection());
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");

        // Act
        provider.shutdown();

        // Assert
        verify(channel).shutdownNow();
        assertThat(provider.isEmpty()).isTrue();
        assertThat(provider.hasSessionProto("s1")).isFalse();
    }

    @Test
    void sweep_evictsIdleChannels() throws InterruptedException {
        // Arrange
        props.setConnectionIdleTimeout(Duration.ofMillis(1));
        when(channelFactory.createChannel(any())).thenReturn(channel);
        provider.channel(connection());
        Thread.sleep(30);

        // Act
        provider.sweep();

        // Assert
        verify(channel).shutdownNow();
        assertThat(provider.isEmpty()).isTrue();
    }

    @Test
    void sweep_nullConnectionIdleTimeout_keepsChannels() {
        // Arrange
        props.setConnectionIdleTimeout(null);
        when(channelFactory.createChannel(any())).thenReturn(channel);
        provider.channel(connection());

        // Act
        provider.sweep();

        // Assert
        assertThat(provider.isEmpty()).isFalse();
    }

    @Test
    void sweep_nullSessionProtoIdleTimeout_keepsProtos() {
        // Arrange
        props.setSessionProtoIdleTimeout(null);
        provider.loadProtoIntoSession("s1", PROTO, "svc.proto");

        // Act
        provider.sweep();

        // Assert
        assertThat(provider.hasSessionProto("s1")).isTrue();
    }

    @Test
    void channelStates_reportsStatePerChannel() {
        // Arrange
        when(channelFactory.createChannel(any())).thenReturn(channel);
        when(channel.getState(false)).thenReturn(io.grpc.ConnectivityState.READY);
        provider.channel(connection());

        // Act
        var states = provider.channelStates();

        // Assert
        assertThat(states).containsValue("READY");
    }

    // === discovery error paths ===

    @Test
    void resolveProtoPath_blank_throws() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("  "));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void discoveryFileBased_protoWithoutService_throws() throws IOException {
        // Arrange
        Files.createDirectories(Path.of(props.getProtoBaseDir()));
        Files.writeString(Path.of(props.getProtoBaseDir(), "empty.proto"), "syntax = \"proto3\";");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "empty.proto");

        // Act
        var thrown = catchThrowable(() -> provider.methods("s1", connection));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось обнаружить методы из proto-файла");
    }

    @Test
    void discoveryReflection_serverDown_throwsWithReflectionHint() {
        // Arrange
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), null);
        props.setReflectionTimeout(Duration.ofSeconds(1));
        when(channelFactory.createChannel(any())).thenReturn(
                io.grpc.inprocess.InProcessChannelBuilder.forName("no-such-server-" + System.nanoTime()).build());

        // Act
        var thrown = catchThrowable(() -> provider.methods("s1", connection));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reflection");
    }

    // === sanitization (indirect) ===

    @Test
    void sanitizeFileName_stripsPathAndAddsExtension() {
        // Arrange

        // Act
        provider.loadProtoIntoSession("s1", PROTO, "../evil");

        // Assert
        assertThat(Files.exists(provider.sessionTempDir("s1").resolve("evil.proto"))).isTrue();
    }

    @Test
    void sanitizeSessionId_blankFallsBackToDefault() {
        // Arrange

        // Act
        provider.loadProtoIntoSession("", PROTO, "svc.proto");

        // Assert
        assertThat(provider.sessionTempDir("")).endsWith(Path.of(System.getProperty("java.io.tmpdir"), "grpc-mcp", "default"));
    }
}
