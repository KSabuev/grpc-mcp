package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DescriptorFileServiceDiscoverer implements ServiceDiscoverer {

    private static final int MAX_WKT_RETRY_ROUNDS = 5;
    private static final Pattern MISSING_IMPORT = Pattern.compile("Import \"([^\"]+)\" was not found");

    String protoFilePath;
    Duration protocTimeout;
    Path wktDir;
    WktExtractor wktExtractor;
    @Getter
    List<String> services = new ArrayList<>();
    @Getter
    List<MethodMeta> methods = new ArrayList<>();

    public DescriptorFileServiceDiscoverer(String protoFilePath, Duration protocTimeout, Path wktDir) {
        this(protoFilePath, protocTimeout, wktDir, new WktExtractor());
    }

    public DescriptorFileServiceDiscoverer(String protoFilePath, Duration protocTimeout,
                                           Path wktDir, WktExtractor wktExtractor) {
        this.protoFilePath = protoFilePath;
        this.protocTimeout = protocTimeout;
        this.wktDir = wktDir;
        this.wktExtractor = wktExtractor;
    }

    @Override
    public void discover() {
        byte[] data = compileProto();
        try {
            var fds = FileDescriptorSet.parseFrom(data);
            var protoList = fds.getFileList();

            if (protoList.isEmpty()) {
                throw new RuntimeException("Дескрипторный набор пуст (не скомпилировано ни одного файла)");
            }

            log.info("Loaded {} file descriptor protos from compiled descriptor set", protoList.size());
            var result = DescriptorUtils.collectServices(protoList);
            services.addAll(result.serviceNames());
            methods.addAll(result.methods());
            log.info("Discovered {} services with {} methods from proto file", services.size(), methods.size());
        } catch (IOException e) {
            throw new RuntimeException("Не удалось разобрать скомпилированный дескрипторный файл", e);
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

    private byte[] compileProto() {
        if (protoFilePath.endsWith(".desc")) {
            return readDescriptorFile();
        }
        if (protoFilePath.endsWith(".proto")) {
            return compileProtoFile();
        }
        throw new IllegalArgumentException("Неподдерживаемый тип файла: " + protoFilePath + ". Ожидается .proto или .desc");
    }

    private byte[] readDescriptorFile() {
        Path descPath = Path.of(protoFilePath);
        if (!Files.exists(descPath)) {
            throw new IllegalArgumentException("Файл дескриптора не найден: " + protoFilePath);
        }
        log.info("Reading pre-compiled descriptor file: {}", protoFilePath);
        try {
            return Files.readAllBytes(descPath);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось прочитать файл дескриптора: " + protoFilePath, e);
        }
    }

    private byte[] compileProtoFile() {
        validateProtocVersion();

        Path protoFile = Path.of(protoFilePath);
        if (!Files.exists(protoFile)) {
            throw new IllegalArgumentException("Proto-файл не найден: " + protoFilePath);
        }

        Path protoDir = protoFile.getParent();
        if (protoDir == null) {
            protoDir = Path.of(".");
        }

        Path descFile;
        try {
            descFile = Files.createTempFile("grpc-mcp-", ".desc");
        } catch (IOException e) {
            throw new RuntimeException("Не удалось создать временный файл для дескриптора", e);
        }

        log.info("Compiling proto file via protoc: {}", protoFilePath);
        long timeoutMs = protocTimeout.toMillis();

        try {
            int round = 0;
            while (true) {
                List<String> cmd = buildProtocCmd(descFile, protoDir, protoFile);
                ProtocResult result;
                try {
                    result = runProtoc(cmd, timeoutMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("protoc was interrupted", e);
                }
                if (result.exitCode() == 0) {
                    break;
                }
                var missing = parseMissingImports(result.output());
                int extracted = extractMissingWkts(missing);
                if (extracted == 0 || ++round >= MAX_WKT_RETRY_ROUNDS) {
                    throw new ProtocCompilationException(result.exitCode(), result.output());
                }
                log.info("Extracted {} missing WKT proto file(s) into {}, retrying protoc (round {})", extracted, wktDir, round);
            }
            if (!Files.exists(descFile) || Files.size(descFile) == 0) {
                throw new RuntimeException("protoc produced empty descriptor file");
            }
            return Files.readAllBytes(descFile);
        } catch (IOException e) {
            throw new RuntimeException("protoc not found or failed to execute. Install protobuf-compiler.", e);
        } finally {
            try {
                Files.deleteIfExists(descFile);
            } catch (IOException ignored) {
            }
        }
    }

    private List<String> buildProtocCmd(Path descFile, Path protoDir, Path protoFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("protoc");
        cmd.add("--descriptor_set_out=" + descFile.toAbsolutePath());
        cmd.add("--include_source_info");
        cmd.add("--include_imports");
        cmd.add("--experimental_allow_proto3_optional");
        cmd.add("-I" + protoDir.toAbsolutePath());
        if (wktDir != null && Files.isDirectory(wktDir)) {
            cmd.add("-I" + wktDir.toAbsolutePath());
        }
        if (Files.exists(Path.of("/usr/include"))) {
            cmd.add("-I/usr/include");
        }
        cmd.add(protoFile.toAbsolutePath().toString());
        return cmd;
    }

    private int extractMissingWkts(List<String> missing) {
        if (wktDir == null || missing.isEmpty()) {
            return 0;
        }
        int extracted = 0;
        try {
            Files.createDirectories(wktDir);
            for (var protoPath : missing) {
                if (wktExtractor.extract(wktDir, protoPath)) {
                    extracted++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not create WKT dir {}: {}", wktDir, e.getMessage());
        }
        return extracted;
    }

    static List<String> parseMissingImports(String protocOutput) {
        var matcher = MISSING_IMPORT.matcher(protocOutput);
        var missing = new LinkedHashSet<String>();
        while (matcher.find()) {
            missing.add(matcher.group(1));
        }
        return new ArrayList<>(missing);
    }

    private record ProtocResult(int exitCode, String output) {
    }

    private ProtocResult runProtoc(List<String> cmd, long timeoutMs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("protoc timed out after " + protocTimeout.getSeconds() + " seconds");
        }
        if (!output.isEmpty()) {
            log.info("protoc output:\n{}", output);
        }
        return new ProtocResult(process.exitValue(), output);
    }
}
