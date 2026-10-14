package io.grpc.mcp.connection;

import io.grpc.ManagedChannel;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.channel.GrpcChannelFactory;
import io.grpc.mcp.grpc.discovery.DescriptorFileServiceDiscoverer;
import io.grpc.mcp.grpc.discovery.GrpcReflectionService;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-client gRPC connections: lazy channel cache, lazy discovery cache,
 * in-memory session proto store (RAM only, nothing persisted to disk),
 * proto file reference resolution and idle eviction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionProvider {

    private final GrpcChannelFactory channelFactory;
    private final GrpcProperties properties;

    private final ConcurrentHashMap<String, ChannelEntry> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DiscoveryEntry> discoveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionProtoEntry> sessionProtos = new ConcurrentHashMap<>();
    private ScheduledExecutorService sweeper;

    private record ChannelEntry(ManagedChannel channel, Instant lastAccess) {}

    private record DiscoveryEntry(List<MethodMeta> methods, Map<String, MethodMeta> byName) {}

    /** Compiled proto kept in RAM for the lifetime of one MCP session. */
    private static final class SessionProtoEntry {
        final List<MethodMeta> methods;
        final Map<String, MethodMeta> byName;
        volatile Instant lastAccess;

        SessionProtoEntry(List<MethodMeta> methods, Map<String, MethodMeta> byName) {
            this.methods = List.copyOf(methods);
            this.byName = Map.copyOf(byName);
            this.lastAccess = Instant.now();
        }

        void touch() {
            lastAccess = Instant.now();
        }
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(protoBaseDir());
            Files.createDirectories(wktDirPath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create proto base or WKT directory", e);
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "grpc-mcp-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleWithFixedDelay(this::sweep, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        channels.forEach((key, entry) -> {
            entry.channel().shutdownNow();
            log.info("Shut down gRPC channel for {}", key);
        });
        channels.clear();
        discoveries.clear();
        sessionProtos.forEach((sessionId, entry) -> {
            deleteSessionTempDir(sessionId);
            log.info("Cleared in-memory proto for session {}", sessionId);
        });
        sessionProtos.clear();
    }

    public ManagedChannel channel(GrpcConnection connection) {
        requireTarget(connection);
        ChannelEntry entry = channels.computeIfAbsent(connection.channelKey(), key -> {
            ManagedChannel channel = channelFactory.createChannel(connection);
            return new ChannelEntry(channel, Instant.now());
        });
        return entry.channel();
    }

    public List<MethodMeta> methods(String sessionId, GrpcConnection connection) {
        return discovery(sessionId, connection).methods();
    }

    public MethodMeta method(String sessionId, GrpcConnection connection, String fullMethodName) {
        MethodMeta meta = discovery(sessionId, connection).byName().get(fullMethodName);
        if (meta == null) {
            throw new IllegalArgumentException("""
                    Метод "%s" не найден.
                    Используйте list_methods, чтобы получить точное полное имя.
                    Пример формата: "com.example.MyService/myMethod".""".formatted(fullMethodName));
        }
        return meta;
    }

    public String discoveryKey(String sessionId, GrpcConnection connection) {
        if (connection.usesReflection()) {
            return "reflection:" + connection.target();
        }
        if (connection.isClientPath()) {
            return "session:" + sessionId;
        }
        return "file:" + resolveProtoPath(connection.protoRef());
    }

    /**
     * Compiles a client-supplied .proto file and keeps the resulting methods in RAM,
     * scoped to one MCP session. Nothing is written to persistent storage: the temp
     * file needed by protoc lives in a per-session tmp dir and is removed when the
     * session entry is evicted or the app shuts down.
     *
     * @return number of discovered methods
     */
    public int loadProtoIntoSession(String sessionId, String content, String name) {
        Path dir = sessionTempDir(sessionId);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitizeFileName(name));
            Files.writeString(file, content);

            ServiceDiscoverer discoverer =
                    new DescriptorFileServiceDiscoverer(file.toString(), properties.getProtocTimeout(), wktDirPath());
            discoverer.discover();
            if (discoverer.getMethods().isEmpty()) {
                log.info("Proto '{}' contains no gRPC methods; keeping it as an import dependency in session {}",
                        name, sessionId);
                return 0;
            }
            Map<String, MethodMeta> byName = new LinkedHashMap<>();
            discoverer.getMethods().forEach(meta -> byName.put(meta.fullMethodName(), meta));
            sessionProtos.put(sessionId, new SessionProtoEntry(discoverer.getMethods(), byName));
            log.info("Loaded {} methods from in-memory proto '{}' for session {}",
                    byName.size(), name, sessionId);
            return byName.size();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load proto file into session memory", e);
        }
    }

    public boolean hasSessionProto(String sessionId) {
        return sessionProtos.containsKey(sessionId);
    }

    public int sessionProtoCount() {
        return sessionProtos.size();
    }

    public void evictChannel(String channelKey) {
        ChannelEntry entry = channels.remove(channelKey);
        if (entry != null) {
            entry.channel().shutdownNow();
            log.info("Evicted gRPC channel for {}", channelKey);
        }
        discoveries.keySet().removeIf(key -> key.startsWith("reflection:")
                && key.substring("reflection:".length()).equals(channelKey.substring(0, channelKey.lastIndexOf('|'))));
    }

    public Map<String, String> channelStates() {
        Map<String, String> states = new LinkedHashMap<>();
        channels.forEach((key, entry) ->
                states.put(key, entry.channel().getState(false).name()));
        return states;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public Path protoBaseDir() {
        return Path.of(properties.getProtoBaseDir()).toAbsolutePath().normalize();
    }

    public Path wktDirPath() {
        return Path.of(properties.getWktDir()).toAbsolutePath().normalize();
    }

    /**
     * Resolves a proto reference (file on the server) to an absolute path,
     * guarding against path traversal. Client paths (/, ~) never reach this method —
     * they are routed to the in-memory session proto store.
     */
    public Path resolveProtoPath(String protoRef) {
        if (protoRef == null || protoRef.isBlank()) {
            throw new IllegalArgumentException("Proto file reference is empty");
        }
        if (protoRef.startsWith(GrpcConnection.LEGACY_UPLOAD_PREFIX)) {
            throw new IllegalArgumentException("""
                    Референсы вида "upload/<uuid>.proto" больше не поддерживаются: proto-файлы
                    теперь загружаются в память сессии тулзой load_proto_file.
                    Загрузите файл через load_proto_file и повторите вызов.
                    """);
        }
        Path base = protoBaseDir();
        if (Path.of(protoRef).isAbsolute()) {
            throw new IllegalArgumentException("Absolute proto file paths are not allowed: " + protoRef);
        }
        Path resolved = base.resolve(protoRef).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Proto file path escapes the allowed base directory: " + protoRef);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(
                    "Proto file not found: " + protoRef + " (looked in " + base + ")");
        }
        return resolved;
    }

    private DiscoveryEntry discovery(String sessionId, GrpcConnection connection) {
        if (connection.isClientPath()) {
            SessionProtoEntry entry = sessionProtos.get(sessionId);
            if (entry == null) {
                throw new IllegalArgumentException(GrpcConnection.clientPathRequest(connection.protoRef()));
            }
            entry.touch();
            return new DiscoveryEntry(entry.methods, entry.byName);
        }
        String key = discoveryKey(sessionId, connection);
        return discoveries.computeIfAbsent(key, k -> {
            ServiceDiscoverer discoverer = createDiscoverer(connection);
            discoverer.discover();
            if (discoverer.getMethods().isEmpty()) {
                throw new IllegalStateException(discoveryErrorMessage(connection, discoverer));
            }
            Map<String, MethodMeta> byName = new LinkedHashMap<>();
            discoverer.getMethods().forEach(meta -> byName.put(meta.fullMethodName(), meta));
            log.info("Discovered {} methods for {}", byName.size(), k);
            return new DiscoveryEntry(List.copyOf(discoverer.getMethods()), Map.copyOf(byName));
        });
    }

    private ServiceDiscoverer createDiscoverer(GrpcConnection connection) {
        if (connection.usesReflection()) {
            return new GrpcReflectionService(channel(connection), "grpc-mcp", properties.getReflectionTimeout());
        }
        Path protoPath = resolveProtoPath(connection.protoRef());
        return new DescriptorFileServiceDiscoverer(protoPath.toString(), properties.getProtocTimeout(), wktDirPath());
    }

    private String discoveryErrorMessage(GrpcConnection connection, ServiceDiscoverer discoverer) {
        if (connection.usesReflection()) {
            return """
                    Методы не обнаружены на %s: gRPC reflection не отвечает.
                    Проверьте адрес/порт в headers (GRPC-Host, GRPC-Port, GRPC-Negotiation)
                    и что на целевом сервере включён server reflection, либо задайте
                    GRPC-ProtoFile (имя файла на сервере либо путь к файлу на машине клиента —
                    тогда прочитайте его и отправьте содержимое через load_proto_file).
                    """
                    .formatted(connection.target());
        }
        if (discoverer instanceof DescriptorFileServiceDiscoverer) {
            return "Не удалось обнаружить методы из proto-файла " + connection.protoRef();
        }
        return "Не удалось обнаружить методы для " + connection.target();
    }

    private void requireTarget(GrpcConnection connection) {
        if (!connection.hasTarget()) {
            throw new IllegalArgumentException("""
                    Адрес gRPC не указан: задайте GRPC-Host (+ GRPC-Port) или GRPC-Address
                    в headers конфига MCP-клиента (opencode.json) либо GRPC_ADDRESS
                    в окружении контейнера.
                    """);
        }
    }

    void sweep() {
        try {
            Duration idleTimeout = properties.getConnectionIdleTimeout();
            Instant cutoff = idleTimeout != null ? Instant.now().minus(idleTimeout) : null;
            if (cutoff != null) {
                channels.entrySet().removeIf(entry -> {
                    if (entry.getValue().lastAccess().isBefore(cutoff)) {
                        entry.getValue().channel().shutdownNow();
                        log.info("Evicted idle gRPC channel for {} (idle > {})", entry.getKey(), idleTimeout);
                        return true;
                    }
                    return false;
                });
            }
            evictIdleSessionProtos();
        } catch (Exception e) {
            log.warn("Connection sweep failed: {}", e.getMessage(), e);
        }
    }

    private void evictIdleSessionProtos() {
        Duration timeout = properties.getSessionProtoIdleTimeout();
        if (timeout == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(timeout);
        sessionProtos.entrySet().removeIf(entry -> {
            if (entry.getValue().lastAccess.isBefore(cutoff)) {
                deleteSessionTempDir(entry.getKey());
                log.info("Evicted in-memory proto for idle session {} (idle > {})", entry.getKey(), timeout);
                return true;
            }
            return false;
        });
    }

    /**
     * Per-session scratch dir (tmp) for the temp proto file that protoc needs.
     * Lives exactly as long as the session entry; removed on eviction/shutdown.
     */
    Path sessionTempDir(String sessionId) {
        return Path.of(System.getProperty("java.io.tmpdir"), "grpc-mcp", sanitizeSessionId(sessionId));
    }

    private void deleteSessionTempDir(String sessionId) {
        Path dir = sessionTempDir(sessionId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Failed to delete session temp file {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean session temp dir {}: {}", dir, e.getMessage());
        }
    }

    private static String sanitizeSessionId(String sessionId) {
        String sanitized = sessionId.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private static String sanitizeFileName(String name) {
        String base = name == null || name.isBlank() ? "service.proto"
                : Path.of(name).getFileName().toString();
        String sanitized = base.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
        if (sanitized.isBlank() || !sanitized.endsWith(".proto")) {
            sanitized = sanitized + ".proto";
        }
        return sanitized;
    }
}
