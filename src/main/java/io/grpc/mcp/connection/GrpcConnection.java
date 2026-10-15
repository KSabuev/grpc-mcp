package io.grpc.mcp.connection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.mcp.config.GrpcProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * gRPC connection parameters for one MCP client request.
 * Resolved per parameter with priority: request header -&gt; container env -&gt; default.
 *
 * @param target      gRPC target in "host:port" form (value for ManagedChannelBuilder.forTarget)
 * @param negotiation PLAINTEXT or TLS
 * @param metadata    static gRPC headers (env default overridden by GRPC-Metadata header)
 * @param protoRef    proto source for discovery: null = reflection, a file name relative to
 *                    the proto base dir (file on the server) or an absolute/&tilde; path
 *                    (file on the client machine, loaded into session memory via load_proto_file)
 */
@Slf4j
public record GrpcConnection(String target, String negotiation, Map<String, String> metadata, String protoRef) {

    public static final String HEADER_ADDRESS = "GRPC-Address";
    public static final String HEADER_HOST = "GRPC-Host";
    public static final String HEADER_PORT = "GRPC-Port";
    public static final String HEADER_NEGOTIATION = "GRPC-Negotiation";
    public static final String HEADER_METADATA = "GRPC-Metadata";
    public static final String HEADER_PROTO_FILE = "GRPC-ProtoFile";
    public static final String CONTEXT_KEY = "grpc-connection";

    /** Legacy refs from the disk-upload era; no longer resolvable, replaced by in-memory session protos. */
    public static final String LEGACY_UPLOAD_PREFIX = "upload/";
    public static final int DEFAULT_PORT = 50051;

    /**
     * @return resolved connection; target may be blank when not provided anywhere
     */
    public static GrpcConnection resolve(HttpServletRequest request, GrpcProperties env, ObjectMapper objectMapper) {
        String target = resolveTarget(request, env);
        String negotiation = firstNonBlank(request.getHeader(HEADER_NEGOTIATION), env.getNegotiationType(), "PLAINTEXT");
        Map<String, String> metadata = resolveMetadata(request, env, objectMapper);
        String protoRef = firstNonBlank(header(request, HEADER_PROTO_FILE), env.getProtoFile());
        return new GrpcConnection(target, negotiation, metadata, protoRef);
    }

    private static String resolveTarget(HttpServletRequest request, GrpcProperties env) {
        String address = header(request, HEADER_ADDRESS);
        if (address != null && !address.isBlank()) {
            return address.trim();
        }
        String host = header(request, HEADER_HOST);
        if (host != null && !host.isBlank()) {
            return host.trim() + ":" + resolvePort(request);
        }
        return env.getAddress();
    }

    /**
     * Reads a request header and restores non-ASCII values:
     * 1. percent-decoding — clients that cannot put raw UTF-8 into HTTP headers
     *    (Node.js / opencode) send values URL-encoded, e.g.
     *    "GRPC-ProtoFile: /home/qwerty/%D0%97%D0%B0%D0%B3%D1%80%D1%83%D0%B7%D0%BA%D0%B8/svc.proto";
     * 2. UTF-8/ISO-8859-1 mojibake fix — servlet containers decode header bytes as
     *    ISO-8859-1, while clients like curl/Go/Python put raw UTF-8 bytes on the wire.
     * Values that do not round-trip (genuine latin-1) are returned unchanged.
     */
    static String header(HttpServletRequest request, String name) {
        return fixMojibake(percentDecode(request.getHeader(name)));
    }

    static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            String decoded = UriUtils.decode(value, StandardCharsets.UTF_8);
            return decoded.indexOf('\uFFFD') >= 0 ? value : decoded;
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    static String fixMojibake(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0xFF) {
                // genuine Unicode (not a latin-1-decoded header byte sequence) — leave as-is
                return value;
            }
            if (c > 0x7F) {
                String fixed = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                return fixed.indexOf('\uFFFD') >= 0 ? value : fixed;
            }
        }
        return value;
    }

    private static int resolvePort(HttpServletRequest request) {
        String headerPort = request.getHeader(HEADER_PORT);
        if (headerPort != null && !headerPort.isBlank()) {
            try {
                int parsed = Integer.parseInt(headerPort.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid GRPC-Port value '{}', falling back to default port {}", headerPort, DEFAULT_PORT);
            }
        }
        return DEFAULT_PORT;
    }

    private static Map<String, String> resolveMetadata(HttpServletRequest request, GrpcProperties env,
                                                        ObjectMapper objectMapper) {
        Map<String, String> result = new LinkedHashMap<>(
                parseMetadata(env.getMetadataJson(), objectMapper, "GRPC_METADATA env"));
        result.putAll(parseMetadata(header(request, HEADER_METADATA), objectMapper, "GRPC-Metadata header"));
        return Map.copyOf(result);
    }

    private static Map<String, String> parseMetadata(String json, ObjectMapper objectMapper, String source) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = new LinkedHashMap<>(
                    objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}));
            parsed.values().removeIf(v -> v == null);
            return parsed;
        } catch (Exception e) {
            log.warn("Failed to parse {} as JSON object of string headers: {}", source, e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * Identity of the physical channel: same target + negotiation + static metadata
     * share one channel; any difference creates a separate one.
     * Metadata is hashed to keep the key compact and to avoid leaking credentials.
     */
    public String channelKey() {
        return target + "|" + negotiation.toUpperCase() + "|" + metadataHash();
    }

    private String metadataHash() {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        try {
            String canonical = metadata.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(java.util.stream.Collectors.joining("\n"));
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public boolean hasTarget() {
        return target != null && !target.isBlank() && !target.contains("${");
    }

    public boolean usesReflection() {
        return protoRef == null;
    }

    /**
     * Client machine path (absolute or ~-relative): the file lives on the MCP client's
     * filesystem, not on the server. Such a proto is loaded into session memory
     * via the load_proto_file tool.
     */
    public boolean isClientPath() {
        return protoRef != null && (protoRef.startsWith("/") || protoRef.startsWith("~"));
    }

    /**
     * Actionable instruction for the LLM: read the client-side file and upload it
     * through load_proto_file (in-memory, session-scoped).
     */
    public static String clientPathRequest(String protoRef) {
        return """
                Proto-файл %s — путь на машине клиента (сервер grpc-mcp не читает файлы с машины клиента).

                1. Проверь, что файл существует, и прочитай его: %s.
                   Если файла нет по этому пути — сообщи пользователю:
                   «Файл отсутствует по пути, указанному в конфиге: %s».
                2. Вызови load_proto_file — сохраняет .proto-файл в памяти сервера grpc-mcp
                   на время этой MCP-сессии (на диск не записывается) и компилирует его,
                   генерируя список gRPC-методов. Аргумент content — полный текст .proto
                   (лимит 1 МБ). Если в файле есть импорты, сначала загрузи импортируемые файлы.
                3. Повтори вызов list_methods — менять конфиг не нужно, файл уже в памяти сессии.
                """.formatted(protoRef, protoRef, protoRef);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
