package io.grpc.mcp.grpc.discovery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Extracts well-known type protos from the classpath
 * (the proto-google-common-protos artifact: google/type, google/api,
 * google/longrunning, google/rpc) into the wkt dir on demand,
 * so protoc can resolve imports via -I.
 *
 * Extraction is atomic (temp file + ATOMIC_MOVE) and idempotent:
 * concurrent sessions extracting the same file cannot corrupt it,
 * and an already-present file is simply overwritten with identical content.
 */
@Slf4j
public class WktExtractor {

    public boolean extract(Path wktDir, String protoPath) {
        if (!isAllowed(protoPath)) {
            log.warn("Rejected WKT extraction for path: {}", protoPath);
            return false;
        }
        var resource = new ClassPathResource(protoPath);
        if (!resource.exists()) {
            return false;
        }
        Path target = wktDir.resolve(protoPath).normalize();
        if (!target.startsWith(wktDir)) {
            return false;
        }
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Extracted WKT proto from classpath: {} -> {}", protoPath, target);
            return true;
        } catch (IOException e) {
            log.warn("Could not extract WKT proto {}: {}", protoPath, e.getMessage());
            return false;
        }
    }

    private static boolean isAllowed(String protoPath) {
        if (protoPath == null || protoPath.isBlank()) {
            return false;
        }
        if (!protoPath.startsWith("google/") || !protoPath.endsWith(".proto")) {
            return false;
        }
        if (protoPath.contains("..") || protoPath.startsWith("/")) {
            return false;
        }
        return true;
    }
}
