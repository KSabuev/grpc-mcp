package io.grpc.mcp.grpc.compilation;

import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.discovery.DescriptorFileServiceDiscoverer;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Compiles client-supplied proto files in a transient scratch directory that lives only
 * for the duration of a single call. The scratch serves as the import-resolution root for
 * protoc; nothing is persisted — the proto cache keeps file contents in RAM and materializes
 * them into a fresh scratch on every load.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProtoCompilationService {

    private final GrpcProperties properties;

    /**
     * Compiles the main proto file together with the previously uploaded dependencies
     * in a scratch directory and returns the discovered methods of the main file.
     *
     * @param importedFiles file name -&gt; content of the already cached dependency protos
     * @param mainFile      sanitized name of the file being loaded
     * @param mainContent   full text of the file being loaded
     * @return methods discovered from the main file
     */
    public List<MethodMeta> compile(Map<String, String> importedFiles, String mainFile, String mainContent) {
        Path scratch;
        try {
            scratch = Files.createTempDirectory("grpc-mcp-load-");
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать временную директорию для компиляции proto", e);
        }
        try {
            importedFiles.forEach((name, content) -> writeFile(scratch, name, content));
            writeFile(scratch, mainFile, mainContent);
            Path wktDir = Path.of(properties.getWktDir()).toAbsolutePath().normalize();
            ServiceDiscoverer discoverer = new DescriptorFileServiceDiscoverer(
                    scratch.resolve(mainFile).toString(), properties.getProtocTimeout(), wktDir);
            discoverer.discover();
            log.info("Compiled proto '{}' with {} import(s) in scratch {}", mainFile, importedFiles.size(), scratch);
            return discoverer.getMethods();
        } finally {
            deleteRecursively(scratch);
        }
    }

    private void writeFile(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать proto-файл " + name, e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to delete scratch file {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean scratch dir {}: {}", dir, e.getMessage());
        }
    }
}
