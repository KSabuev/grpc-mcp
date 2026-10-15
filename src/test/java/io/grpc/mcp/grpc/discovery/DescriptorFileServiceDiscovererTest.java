package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

class DescriptorFileServiceDiscovererTest {

    @Test
    void unsupportedFileExtension_throwsException() {
        // Arrange
        var discoverer = new DescriptorFileServiceDiscoverer("test.txt", Duration.ofSeconds(10), null);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неподдерживаемый тип файла");
    }

    @Test
    void descriptorFileNotFound_throwsException() {
        // Arrange
        var discoverer = new DescriptorFileServiceDiscoverer(
                "/nonexistent/path/test.desc", Duration.ofSeconds(10), null);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Файл дескриптора не найден");
    }

    @Test
    void descriptorFileReadSuccess(@TempDir Path tempDir) throws Exception {
        // Arrange
        var fds = FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("test.proto").setPackage("pkg")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Input"))
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Output"))
                        .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                                .setName("TestSvc")
                                .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                                        .setName("DoIt")
                                        .setInputType("pkg.Input")
                                        .setOutputType("pkg.Output"))))
                .build();

        Path descFile = tempDir.resolve("test.desc");
        Files.write(descFile, fds.toByteArray());

        var discoverer = new DescriptorFileServiceDiscoverer(
                descFile.toAbsolutePath().toString(), Duration.ofSeconds(10), null);

        // Act
        discoverer.discover();

        // Assert
        assertThat(discoverer.getServices()).containsExactly("pkg.TestSvc");
        assertThat(discoverer.getMethods()).hasSize(1);
        assertThat(discoverer.getMethods().get(0).fullMethodName()).isEqualTo("pkg.TestSvc/DoIt");
    }

    @Test
    void protoFileNotFound_throwsException() {
        // Arrange
        var discoverer = new DescriptorFileServiceDiscoverer(
                "/nonexistent/path/test.proto", Duration.ofSeconds(10), null);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proto-файл не найден");
    }

    @Test
    void rediscover_defaultIsNoop() {
        // Arrange
        var discoverer = new DescriptorFileServiceDiscoverer("test.proto", java.time.Duration.ofSeconds(10), null);

        // Act
        discoverer.rediscover(mock(io.grpc.ManagedChannel.class));

        // Assert
        assertThat(discoverer.getServices()).isEmpty();
    }

    @Test
    void emptyDescriptorSet_throwsException() throws Exception {
        // Arrange
        Path descFile = java.nio.file.Files.createTempFile("empty-", ".desc");
        java.nio.file.Files.write(descFile, FileDescriptorSet.newBuilder().build().toByteArray());

        var discoverer = new DescriptorFileServiceDiscoverer(
                descFile.toAbsolutePath().toString(), Duration.ofSeconds(10), null);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Дескрипторный набор пуст");
    }

    @Test
    void protocFailure_returnsExitCodeError(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path proto = tempDir.resolve("broken.proto");
        java.nio.file.Files.writeString(proto, "syntax = \"proto3\";\nmessage Broken {");

        var discoverer = new DescriptorFileServiceDiscoverer(
                proto.toAbsolutePath().toString(), Duration.ofSeconds(10), null);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("protoc failed with exit code");
    }

    @Test
    void protocTimeout_throwsException(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path proto = tempDir.resolve("slow.proto");
        java.nio.file.Files.writeString(proto, """
                syntax = "proto3";
                package t;
                service S { rpc M (In) returns (Out); }
                message In { string a = 1; }
                message Out { string b = 1; }
                """);

        var discoverer = new DescriptorFileServiceDiscoverer(
                proto.toAbsolutePath().toString(), Duration.ofMillis(1), null);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("protoc timed out");
    }

    @Test
    void wktDirUsedForImports(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path wkt = tempDir.resolve("wkt");
        java.nio.file.Files.createDirectories(wkt.resolve("google/type"));
        java.nio.file.Files.writeString(wkt.resolve("google/type/mytype.proto"), """
                syntax = "proto3";
                package google.type;
                message MyType { string value = 1; }
                """);
        Path proto = tempDir.resolve("main.proto");
        java.nio.file.Files.writeString(proto, """
                syntax = "proto3";
                import "google/type/mytype.proto";
                package t;
                service S { rpc M (google.type.MyType) returns (google.type.MyType); }
                """);

        var discoverer = new DescriptorFileServiceDiscoverer(
                proto.toAbsolutePath().toString(), Duration.ofSeconds(10), wkt);

        // Act
        discoverer.discover();

        // Assert
        assertThat(discoverer.getMethods())
                .extracting(MethodMeta::fullMethodName)
                .contains("t.S/M");
    }

    @Test
    void missingWktImport_extractedOnDemandAndCompiled(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path proto = tempDir.resolve("main.proto");
        Files.writeString(proto, """
                syntax = "proto3";
                package main;
                import "google/type/decimal.proto";
                service Svc { rpc DoIt (Msg) returns (Msg); }
                message Msg { google.type.Decimal price = 1; }
                """);
        Path wkt = tempDir.resolve("wkt");

        var discoverer = new DescriptorFileServiceDiscoverer(
                proto.toAbsolutePath().toString(), Duration.ofSeconds(10), wkt);

        // Act
        discoverer.discover();

        // Assert
        assertThat(discoverer.getServices()).contains("main.Svc");
        assertThat(Files.exists(wkt.resolve("google/type/decimal.proto"))).isTrue();
    }

    @Test
    void missingNonWktImport_throwsProtocError(@TempDir Path tempDir) throws Exception {
        // Arrange
        Path proto = tempDir.resolve("main.proto");
        Files.writeString(proto, """
                syntax = "proto3";
                package main;
                import "vendor/missing.proto";
                service Svc { rpc DoIt (Msg) returns (Msg); }
                message Msg { string id = 1; }
                """);
        Path wkt = tempDir.resolve("wkt");

        var discoverer = new DescriptorFileServiceDiscoverer(
                proto.toAbsolutePath().toString(), Duration.ofSeconds(10), wkt);

        // Act
        var thrown = catchThrowable(discoverer::discover);

        // Assert
        assertThat(thrown).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("protoc failed with exit code");
        assertThat(Files.exists(wkt.resolve("vendor/missing.proto"))).isFalse();
    }

    @Test
    void parseMissingImports_extractsAllUniquePaths() {
        // Arrange
        var output = """
                main.proto:3:1: Import "google/type/decimal.proto" was not found or had errors.
                main.proto:4:1: Import "google/api/http.proto" was not found or had errors.
                main.proto:3:1: Import "google/type/decimal.proto" was not found or had errors.
                """;

        // Act
        var missing = DescriptorFileServiceDiscoverer.parseMissingImports(output);

        // Assert
        assertThat(missing).containsExactly("google/type/decimal.proto", "google/api/http.proto");
    }

    @Test
    void parseMissingImports_noImports_returnsEmpty() {
        // Arrange
        var output = "main.proto:5:1: Expected identifier.";

        // Act
        var missing = DescriptorFileServiceDiscoverer.parseMissingImports(output);

        // Assert
        assertThat(missing).isEmpty();
    }
}
