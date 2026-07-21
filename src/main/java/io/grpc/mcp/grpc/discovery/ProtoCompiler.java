package io.grpc.mcp.grpc.discovery;

import io.grpc.mcp.exceptions.DiscoveryException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class ProtoCompiler {

    private final String protoFilePath;
    private final Duration protocTimeout;

    public ProtoCompiler(String protoFilePath, Duration protocTimeout) {
        this.protoFilePath = protoFilePath;
        this.protocTimeout = protocTimeout;
    }

    public byte[] compile() {
        if (protoFilePath.endsWith(".desc")) {
            return readDescriptorFile();
        }
        if (protoFilePath.endsWith(".proto")) {
            return compileProtoFile();
        }
        throw new DiscoveryException("Unsupported file type: " + protoFilePath + ". Expected .proto or .desc");
    }

    private byte[] readDescriptorFile() {
        Path descPath = Path.of(protoFilePath);
        if (!Files.exists(descPath)) {
            throw new DiscoveryException("Descriptor file not found: " + protoFilePath);
        }
        log.info("Reading pre-compiled descriptor file: {}", protoFilePath);
        try {
            return Files.readAllBytes(descPath);
        } catch (IOException e) {
            throw new DiscoveryException("Failed to read descriptor file: " + protoFilePath, e);
        }
    }

    private byte[] compileProtoFile() {
        validateProtocVersion();

        Path protoFile = Path.of(protoFilePath);
        if (!Files.exists(protoFile)) {
            throw new DiscoveryException("Proto file not found: " + protoFilePath);
        }

        Path protoDir = protoFile.getParent();
        if (protoDir == null) {
            protoDir = Path.of(".");
        }

        Path descFile;
        try {
            descFile = Files.createTempFile("grpc-mcp-", ".desc");
        } catch (IOException e) {
            throw new DiscoveryException("Failed to create temp file for descriptor", e);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("protoc");
        cmd.add("--descriptor_set_out=" + descFile.toAbsolutePath());
        cmd.add("--include_source_info");
        cmd.add("--include_imports");
        cmd.add("--experimental_allow_proto3_optional");
        cmd.add("-I" + protoDir.toAbsolutePath());
        if (Files.exists(Path.of("/usr/include"))) {
            cmd.add("-I/usr/include");
        }
        cmd.add(protoFile.toAbsolutePath().toString());

        log.info("Compiling proto file via protoc: {}", protoFilePath);
        long timeoutMs = protocTimeout.toMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            int exitCode = completed ? process.exitValue() : -1;
            if (!output.isEmpty()) {
                log.info("protoc output:\n{}", output);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new DiscoveryException("protoc timed out after " + protocTimeout.getSeconds() + " seconds");
            }
            if (exitCode != 0) {
                throw new DiscoveryException("protoc failed with exit code " + exitCode + ":\n" + output);
            }
            if (!Files.exists(descFile) || Files.size(descFile) == 0) {
                throw new DiscoveryException("protoc produced empty descriptor file:\n" + output);
            }
        } catch (IOException e) {
            throw new DiscoveryException("protoc not found or failed to execute. Install protobuf-compiler.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiscoveryException("protoc was interrupted", e);
        }

        try {
            return Files.readAllBytes(descFile);
        } catch (IOException e) {
            throw new DiscoveryException("Failed to read compiled descriptor file", e);
        } finally {
            try {
                Files.deleteIfExists(descFile);
            } catch (IOException ignored) {
            }
        }
    }

    private void validateProtocVersion() {
        try {
            var process = new ProcessBuilder("protoc", "--version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                log.warn("Could not determine protoc version: {}", output);
                return;
            }
            log.info("Using protoc version: {}", output);
        } catch (Exception e) {
            log.warn("Could not validate protoc version: {}", e.getMessage());
        }
    }
}
