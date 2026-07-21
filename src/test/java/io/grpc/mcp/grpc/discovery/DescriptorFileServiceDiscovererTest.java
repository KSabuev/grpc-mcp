package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.grpc.mcp.exceptions.DiscoveryException;
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
        var compiler = new ProtoCompiler("test.txt", Duration.ofSeconds(10));
        var parser = new DescriptorParser();
        var discoverer = new DescriptorFileServiceDiscoverer(compiler, parser);

        assertThatThrownBy(discoverer::discover)
                .isInstanceOf(DiscoveryException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void descriptorFileNotFound_throwsException() {
        var compiler = new ProtoCompiler("/nonexistent/path/test.desc", Duration.ofSeconds(10));
        var parser = new DescriptorParser();
        var discoverer = new DescriptorFileServiceDiscoverer(compiler, parser);

        assertThatThrownBy(discoverer::discover)
                .isInstanceOf(DiscoveryException.class)
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

        var compiler = new ProtoCompiler(descFile.toAbsolutePath().toString(), Duration.ofSeconds(10));
        var parser = new DescriptorParser();
        var discoverer = new DescriptorFileServiceDiscoverer(compiler, parser);
        discoverer.discover();

        assertThat(discoverer.getServices()).containsExactly("pkg.TestSvc");
        assertThat(discoverer.getMethods()).hasSize(1);
        assertThat(discoverer.getMethods().get(0).fullMethodName()).isEqualTo("pkg.TestSvc/DoIt");
    }

    @Test
    void protoFileNotFound_throwsException() {
        var compiler = new ProtoCompiler("/nonexistent/path/test.proto", Duration.ofSeconds(10));
        var parser = new DescriptorParser();
        var discoverer = new DescriptorFileServiceDiscoverer(compiler, parser);

        assertThatThrownBy(discoverer::discover)
                .isInstanceOf(DiscoveryException.class)
                .hasMessageContaining("Proto file not found");
    }
}
