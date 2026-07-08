package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorFileServiceDiscovererTest {

    @Test
    void unsupportedFileExtension_throwsException() {
        var discoverer = new DescriptorFileServiceDiscoverer("test.txt", Duration.ofSeconds(10));

        assertThatThrownBy(discoverer::discover)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void descriptorFileNotFound_throwsException() {
        var discoverer = new DescriptorFileServiceDiscoverer(
                "/nonexistent/path/test.desc", Duration.ofSeconds(10));

        assertThatThrownBy(discoverer::discover)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Descriptor file not found");
    }

    @Test
    void descriptorFileReadSuccess(@TempDir Path tempDir) throws Exception {
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
                descFile.toAbsolutePath().toString(), Duration.ofSeconds(10));
        discoverer.discover();

        assertThat(discoverer.getServices()).containsExactly("pkg.TestSvc");
        assertThat(discoverer.getMethods()).hasSize(1);
        assertThat(discoverer.getMethods().get(0).fullMethodName()).isEqualTo("pkg.TestSvc/DoIt");
    }

    @Test
    void protoFileNotFound_throwsException() {
        var discoverer = new DescriptorFileServiceDiscoverer(
                "/nonexistent/path/test.proto", Duration.ofSeconds(10));

        assertThatThrownBy(discoverer::discover)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proto file not found");
    }
}
