package io.grpc.mcp.connection;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.config.ProtoCacheConfig;
import io.grpc.mcp.grpc.channel.GrpcChannelFactory;
import io.grpc.mcp.grpc.compilation.ProtoCompilationService;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ProtocCompilationException;

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
        props.setProtoCacheIdleTimeout(Duration.ofHours(1));
        props.setProtocTimeout(Duration.ofSeconds(10));
        props.setReflectionTimeout(Duration.ofSeconds(5));
        provider = new ConnectionProvider(channelFactory, props, new ProtoCompilationService(props),
                ProtoCacheConfig.nativeCache(ProtoCacheConfig.cacheManager(props)));
    }

    private ConnectionProvider newProvider() {
        return new ConnectionProvider(channelFactory, props, new ProtoCompilationService(props),
                ProtoCacheConfig.nativeCache(ProtoCacheConfig.cacheManager(props)));
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
                .hasMessageContaining("выходит за пределы");
    }

    @Test
    void resolveProtoPath_absolutePathRejected() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("/etc/passwd"));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Абсолютные");
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
        var methods = provider.methods(connection);

        // Assert
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
    }

    @Test
    void discoveryResult_cached() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");
        provider.methods(connection);

        // Act
        var cached = provider.methods(connection);

        // Assert
        assertThat(cached).isNotNull();
    }

    @Test
    void method_lookupByFullName() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");

        // Act
        var meta = provider.method(connection, "test.TestService/DoIt");

        // Assert
        assertThat(meta.serviceName()).isEqualTo("test.TestService");
    }

    @Test
    void method_unknown_throws() throws IOException {
        // Arrange
        writeBaseProto("svc.proto");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "svc.proto");

        // Act
        var thrown = catchThrowable(() -> provider.method(connection, "test.TestService/Nope"));

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
        var keyA = provider.discoveryKey(c1);

        // Assert
        assertThat(keyA).isNotEqualTo(provider.discoveryKey(c2));
    }

    @Test
    void discoveryKey_clientPath_usesProtoCacheKey() {
        // Arrange
        var conn = clientPathConnection("/home/user/svc.proto");

        // Act
        var key = provider.discoveryKey(conn);

        // Assert
        assertThat(key).isEqualTo("cache:" + conn.protoCacheKey());
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

    @Test
    void clientPath_withoutLoadedProto_throwsLlmRequest() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");

        // Act
        var thrown = catchThrowable(() -> provider.methods(connection));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/home/user/svc.proto")
                .hasMessageContaining("load_proto_file")
                .hasMessageContaining("отсутствует по пути");
    }

    @Test
    void loadProto_methodsAvailableForConnection() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        var methods = provider.methods(connection);

        // Assert
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
    }

    @Test
    void loadProto_methodLookup() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        var meta = provider.method(connection, "test.TestService/DoIt");

        // Assert
        assertThat(meta.serviceName()).isEqualTo("test.TestService");
    }

    @Test
    void loadProto_changedContent_recompiles() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        var result = provider.loadProto(connection, PROTO_TWO, "svc.proto");

        // Assert
        assertThat(result.cached()).isFalse();
        var methods = provider.methods(connection);
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt", "test.TestService/Other");
    }

    @Test
    void loadProto_sameContent_servedFromCache() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        var result = provider.loadProto(connection, PROTO, "svc.proto");

        // Assert
        assertThat(result.cached()).isTrue();
        assertThat(result.methodCount()).isEqualTo(1);
        assertThat(provider.protoCacheCount()).isEqualTo(1);
    }

    @Test
    void protoCache_sharedAcrossSessions_sameConnection() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        var methods = provider.methods(connection);

        // Assert
        assertThat(methods).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
        assertThat(provider.hasCachedProto(connection)).isTrue();
    }

    @Test
    void protoCache_isolatedByConnectionKey() {
        // Arrange
        provider.loadProto(clientPathConnection("/home/user/svc.proto"), PROTO, "svc.proto");
        var other = clientPathConnection("/home/user/other.proto");

        // Act
        var thrown = catchThrowable(() -> provider.methods(other));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load_proto_file");
        assertThat(provider.hasCachedProto(other)).isFalse();
    }

    @Test
    void protoCacheKey_hostPortAndBaseName_only() {
        // Arrange
        var a = clientPathConnection("/home/user/protos/svc.proto");
        var b = clientPathConnection("/other/path/svc.proto");
        var c = clientPathConnection("/home/user/protos/svc.proto");

        // Act
        var keyA = a.protoCacheKey();

        // Assert
        assertThat(keyA).isEqualTo(b.protoCacheKey());
        assertThat(keyA).isEqualTo(c.protoCacheKey());
        assertThat(keyA).isEqualTo("client:50051-svc.proto");
    }

    @Test
    void protoCacheKey_differentHostOrPort_isolated() {
        // Arrange
        var base = clientPathConnection("/home/user/svc.proto");
        var otherHost = new GrpcConnection("other:50051", "PLAINTEXT", Map.of(), "/home/user/svc.proto");
        var otherPort = new GrpcConnection("client:6000", "PLAINTEXT", Map.of(), "/home/user/svc.proto");

        // Act
        var key = base.protoCacheKey();

        // Assert
        assertThat(key).isNotEqualTo(otherHost.protoCacheKey());
        assertThat(key).isNotEqualTo(otherPort.protoCacheKey());
    }

    @Test
    void loadProto_noMethods_keptAsDependency() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");

        // Act
        var result = provider.loadProto(connection, "syntax = \"proto3\";", "empty.proto");

        // Assert
        assertThat(result.methodCount()).isZero();
        assertThat(provider.hasCachedProto(connection)).isTrue();
        var thrown = catchThrowable(() -> provider.methods(connection));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load_proto_file");
    }

    @Test
    void lruCap_evictsLeastRecentlyUsed() {
        // Arrange
        props.setProtoCacheMaxEntries(1);
        provider = newProvider();
        var first = clientPathConnection("/home/user/a.proto");
        var second = clientPathConnection("/home/user/b.proto");
        provider.loadProto(first, PROTO, "a.proto");
        provider.loadProto(second, PROTO_TWO, "b.proto");
        provider.maintainProtoCache();

        // Act
        var thrown = catchThrowable(() -> provider.methods(first));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("load_proto_file");
        assertThat(provider.hasCachedProto(second)).isTrue();
        assertThat(provider.protoCacheCount()).isEqualTo(1);
    }

    @Test
    void nothingStoredOnServerDisk() throws IOException {
        // Arrange
        Files.createDirectories(Path.of(props.getProtoBaseDir()));
        var connection = clientPathConnection("/home/user/svc.proto");

        // Act
        provider.loadProto(connection, PROTO, "svc.proto");

        // Assert
        assertThat(Files.list(Path.of(props.getProtoBaseDir()))).isEmpty();
        assertThat(Files.exists(Path.of(props.getProtoBaseDir()).getParent().resolve("uploads"))).isFalse();
        assertThat(leftoverScratchDirs()).isZero();
    }

    @Test
    void idleProtoCache_evictedAfterIdleTimeout() throws InterruptedException {
        // Arrange
        props.setProtoCacheIdleTimeout(Duration.ofMillis(20));
        provider = newProvider();
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");
        Thread.sleep(300);

        // Act
        provider.maintainProtoCache();

        // Assert
        assertThat(provider.hasCachedProto(connection)).isFalse();
    }

    @Test
    void activeProtoCacheEntry_notEvicted() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        provider.sweep();

        // Assert
        assertThat(provider.hasCachedProto(connection)).isTrue();
    }

    @Test
    void shutdown_clearsProtoCache() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        provider.shutdown();

        // Assert
        assertThat(provider.hasCachedProto(connection)).isFalse();
    }

    private static long leftoverScratchDirs() throws IOException {
        return Files.list(Path.of(System.getProperty("java.io.tmpdir")))
                .filter(p -> p.getFileName().toString().startsWith("grpc-mcp-load-"))
                .count();
    }

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
    void loadProto_missingWktImport_extractedAndCompiled() throws Exception {
        // Arrange
        props.setWktDir(tempDir.resolve("wkt-lazy").toString());
        var connection = clientPathConnection("/home/user/main.proto");
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
        var result = provider.loadProto(connection, proto, "main.proto");

        // Assert
        assertThat(result.methodCount()).isEqualTo(1);
        assertThat(provider.methods(connection))
                .extracting(MethodMeta::fullMethodName)
                .contains("main.Svc/DoIt");
        assertThat(Files.exists(tempDir.resolve("wkt-lazy/google/api/annotations.proto"))).isTrue();
    }

    @Test
    void shutdown_afterInit_clearsAll() {
        // Arrange
        provider.init();
        when(channelFactory.createChannel(any())).thenReturn(channel);
        provider.channel(connection());
        provider.loadProto(clientPathConnection("/home/user/svc.proto"), PROTO, "svc.proto");

        // Act
        provider.shutdown();

        // Assert
        verify(channel).shutdownNow();
        assertThat(provider.isEmpty()).isTrue();
        assertThat(provider.hasCachedProto(clientPathConnection("/home/user/svc.proto"))).isFalse();
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
    void sweep_nullProtoCacheIdleTimeout_keepsProtos() {
        // Arrange
        props.setProtoCacheIdleTimeout(null);
        provider = newProvider();
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        provider.sweep();

        // Assert
        assertThat(provider.hasCachedProto(connection)).isTrue();
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

    @Test
    void resolveProtoPath_blank_throws() {
        // Arrange

        // Act
        var thrown = catchThrowable(() -> provider.resolveProtoPath("  "));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не задана");
    }

    @Test
    void discoveryFileBased_protoWithoutService_throws() throws IOException {
        // Arrange
        Files.createDirectories(Path.of(props.getProtoBaseDir()));
        Files.writeString(Path.of(props.getProtoBaseDir(), "empty.proto"), "syntax = \"proto3\";");
        var connection = new GrpcConnection("client:50051", "PLAINTEXT", Map.of(), "empty.proto");

        // Act
        var thrown = catchThrowable(() -> provider.methods(connection));

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
        var thrown = catchThrowable(() -> provider.methods(connection));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reflection");
    }

    @Test
    void sanitizeFileName_stripsPathAndAddsExtension() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");

        // Act
        provider.loadProto(connection, PROTO, "../evil");
        var main = """
                syntax = "proto3";
                package main;
                import "evil.proto";
                service Svc { rpc DoIt (test.Input) returns (test.Output); }
                """;

        // Assert: the file is stored under the sanitized name "evil.proto" and is importable
        provider.loadProto(connection, main, "main.proto");
        assertThat(provider.methods(connection)).extracting(MethodMeta::fullMethodName)
                .contains("main.Svc/DoIt");
        assertThat(provider.method(connection, "main.Svc/DoIt").serviceName()).isEqualTo("main.Svc");
    }

    @Test
    void loadProto_invalidProto_throwsAndLeavesCacheClean() throws IOException {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");

        // Act
        var thrown = catchThrowable(() ->
                provider.loadProto(connection, "syntax = \"proto3\";\nmessage Broken {", "svc.proto"));

        // Assert
        assertThat(thrown).isInstanceOf(ProtocCompilationException.class)
                .hasMessageContaining("protoc failed with exit code");
        assertThat(provider.hasCachedProto(connection)).isFalse();
        assertThat(leftoverScratchDirs()).isZero();

        // a retry with the fixed file succeeds
        var result = provider.loadProto(connection, PROTO, "svc.proto");
        assertThat(result.cached()).isFalse();
        assertThat(provider.methods(connection)).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
    }

    @Test
    void loadProto_invalidProto_onTopOfGoodVersion_keepsGoodVersion() {
        // Arrange
        var connection = clientPathConnection("/home/user/svc.proto");
        provider.loadProto(connection, PROTO, "svc.proto");

        // Act
        var thrown = catchThrowable(() ->
                provider.loadProto(connection, "syntax = \"proto3\";\nmessage Broken {", "svc.proto"));

        // Assert: the last good version is untouched
        assertThat(thrown).isInstanceOf(ProtocCompilationException.class);
        assertThat(provider.methods(connection)).extracting(MethodMeta::fullMethodName)
                .contains("test.TestService/DoIt");
    }
}
