package io.grpc.mcp.connection;

import com.github.benmanes.caffeine.cache.Cache;
import io.grpc.ManagedChannel;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.channel.GrpcChannelFactory;
import io.grpc.mcp.grpc.compilation.ProtoCompilationService;
import io.grpc.mcp.grpc.discovery.DescriptorFileServiceDiscoverer;
import io.grpc.mcp.grpc.discovery.GrpcReflectionService;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ProtocCompilationException;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-client gRPC connections: lazy channel cache, lazy discovery cache,
 * server-side proto cache for client paths (RAM only, keyed by
 * host:port + proto file name, shared across MCP sessions, nothing persisted
 * to disk), proto file reference resolution and idle eviction.
 */
@Slf4j
@Component
public class ConnectionProvider {

    private final GrpcChannelFactory channelFactory;
    private final GrpcProperties properties;
    private final ProtoCompilationService compilationService;
    private final Cache<Object, Object> protoCache;

    private final ConcurrentHashMap<String, ChannelEntry> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DiscoveryEntry> discoveries = new ConcurrentHashMap<>();

    public ConnectionProvider(GrpcChannelFactory channelFactory, GrpcProperties properties,
                              ProtoCompilationService compilationService,
                              Cache<Object, Object> protoCacheNative) {
        this.channelFactory = channelFactory;
        this.properties = properties;
        this.compilationService = compilationService;
        this.protoCache = protoCacheNative;
    }

    private record ChannelEntry(ManagedChannel channel, Instant lastAccess) {}

    private record DiscoveryEntry(List<MethodMeta> methods, Map<String, MethodMeta> byName) {}

    /** Result of a proto load: method count of the loaded file and whether it was served from cache. */
    public record ProtoLoadResult(int methodCount, boolean cached) {}

    /** One uploaded file inside a cached proto entry (all mutations happen under the entry monitor). */
    private static final class CachedFile {
        final String content;
        final String sha256;
        final int methodCount;
        final Set<String> methodNames;

        CachedFile(String content, String sha256, int methodCount, Set<String> methodNames) {
            this.content = content;
            this.sha256 = sha256;
            this.methodCount = methodCount;
            this.methodNames = Set.copyOf(methodNames);
        }
    }

    /**
     * Server-side proto cache entry for one connection key (host:port + proto file name).
     * Lives in RAM across MCP sessions until idle eviction, LRU eviction or shutdown.
     * All mutations of files/methods/byName happen under the entry monitor.
     */
    private static final class CachedProtoEntry {
        final Map<String, CachedFile> files = new LinkedHashMap<>();
        final List<MethodMeta> methods = new ArrayList<>();
        final Map<String, MethodMeta> byName = new LinkedHashMap<>();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(protoBaseDir());
            Files.createDirectories(wktDirPath());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create proto base or WKT directory", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        channels.forEach((key, entry) -> {
            entry.channel().shutdownNow();
            log.info("Shut down gRPC channel for {}", key);
        });
        channels.clear();
        discoveries.clear();
        protoCache.asMap().keySet().forEach(key -> log.info("Cleared cached proto for {}", key));
        protoCache.invalidateAll();
    }

    /** Forces Caffeine maintenance: applies pending expirations/evictions (used by tests). */
    void maintainProtoCache() {
        protoCache.cleanUp();
    }

    public ManagedChannel channel(GrpcConnection connection) {
        requireTarget(connection);
        ChannelEntry entry = channels.computeIfAbsent(connection.channelKey(), key -> {
            ManagedChannel channel = channelFactory.createChannel(connection);
            return new ChannelEntry(channel, Instant.now());
        });
        return entry.channel();
    }

    public List<MethodMeta> methods(GrpcConnection connection) {
        return discovery(connection).methods();
    }

    public MethodMeta method(GrpcConnection connection, String fullMethodName) {
        MethodMeta meta = discovery(connection).byName().get(fullMethodName);
        if (meta == null) {
            throw new IllegalArgumentException("""
                    Метод "%s" не найден.
                    Используй list_methods, чтобы получить точное полное имя.
                    Пример формата: "com.example.MyService/myMethod".""".formatted(fullMethodName));
        }
        return meta;
    }

    public String discoveryKey(GrpcConnection connection) {
        if (connection.usesReflection()) {
            return "reflection:" + connection.target();
        }
        if (connection.isClientPath()) {
            return "cache:" + connection.protoCacheKey();
        }
        return "file:" + resolveProtoPath(connection.protoRef());
    }

    /**
     * Compiles a client-supplied .proto file (in a transient scratch, via
     * ProtoCompilationService) and commits the result into the server-side proto
     * cache (RAM only), keyed by host:port + proto file name, shared across MCP
     * sessions. The cache entry is committed only on successful compilation; on
     * failure a freshly created empty entry is rolled back. If the exact same
     * content for the same file name is already cached, the entry is served from
     * cache without running protoc.
     *
     * @return method count of the loaded file and whether it was served from cache
     * @throws ProtocCompilationException when protoc rejects the file (syntax error, unresolved import)
     */
    public ProtoLoadResult loadProto(GrpcConnection connection, String content, String name) {
        if (!connection.isClientPath()) {
            throw new IllegalArgumentException("loadProto is only defined for client paths");
        }
        String key = connection.protoCacheKey();
        String fileName = sanitizeFileName(name);
        CachedProtoEntry entry = (CachedProtoEntry) protoCache.get(key, k -> new CachedProtoEntry());
        try {
            synchronized (entry) {
                String hash = sha256Hex(content);
                CachedFile existing = entry.files.get(fileName);
                if (existing != null && existing.sha256.equals(hash)) {
                    log.info("Proto cache hit for {} (file '{}')", key, fileName);
                    return new ProtoLoadResult(existing.methodCount, true);
                }
                List<MethodMeta> newMethods = compilationService.compile(importedFiles(entry), fileName, content);
                Set<String> newNames = newMethods.stream()
                        .map(MethodMeta::fullMethodName)
                        .collect(Collectors.toSet());

                if (existing != null) {
                    entry.byName.keySet().removeAll(existing.methodNames);
                    entry.methods.removeIf(meta -> existing.methodNames.contains(meta.fullMethodName()));
                }
                for (MethodMeta meta : newMethods) {
                    entry.byName.put(meta.fullMethodName(), meta);
                    entry.methods.add(meta);
                }
                entry.files.put(fileName, new CachedFile(content, hash, newMethods.size(), newNames));
                log.info("Loaded {} methods from proto '{}' into cache {}", newMethods.size(), fileName, key);
                return new ProtoLoadResult(newMethods.size(), false);
            }
        } catch (RuntimeException e) {
            rollbackFreshEntry(key, entry);
            throw e;
        }
    }

    private Map<String, String> importedFiles(CachedProtoEntry entry) {
        Map<String, String> imported = new LinkedHashMap<>();
        entry.files.forEach((storedName, stored) -> imported.put(storedName, stored.content));
        return imported;
    }

    /**
     * Removes a cache entry left behind by a failed load when it holds no files
     * and no methods, so a broken first upload does not pollute the cache.
     */
    private void rollbackFreshEntry(String key, CachedProtoEntry entry) {
        synchronized (entry) {
            if (entry.files.isEmpty() && entry.methods.isEmpty()) {
                protoCache.invalidate(key);
                log.info("Removed empty cache entry for {} after failed proto load", key);
            }
        }
    }

    public boolean hasCachedProto(GrpcConnection connection) {
        if (!connection.isClientPath()) {
            return false;
        }
        return protoCache.getIfPresent(connection.protoCacheKey()) != null;
    }

    public int protoCacheCount() {
        return (int) protoCache.estimatedSize();
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
     * they are routed to the server-side proto cache.
     */
    public Path resolveProtoPath(String protoRef) {
        if (protoRef == null || protoRef.isBlank()) {
            throw new IllegalArgumentException("Ссылка на proto-файл не задана.");
        }
        if (protoRef.startsWith(GrpcConnection.LEGACY_UPLOAD_PREFIX)) {
            throw new IllegalArgumentException("""
                    Референсы вида "upload/<uuid>.proto" больше не поддерживаются: proto-файлы
                    теперь загружаются в кэш сервера тулзой load_proto_file.
                    Загрузи файл через load_proto_file и повтори вызов.
                    """);
        }
        Path base = protoBaseDir();
        if (Path.of(protoRef).isAbsolute()) {
            throw new IllegalArgumentException("Абсолютные пути к proto-файлам не разрешены: " + protoRef);
        }
        Path resolved = base.resolve(protoRef).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Путь к proto-файлу выходит за пределы базовой директории: " + protoRef);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(
                    "Proto file not found: " + protoRef + " (looked in " + base + ")");
        }
        return resolved;
    }

    private DiscoveryEntry discovery(GrpcConnection connection) {
        if (connection.isClientPath()) {
            CachedProtoEntry entry = (CachedProtoEntry) protoCache.getIfPresent(connection.protoCacheKey());
            if (entry == null) {
                throw new IllegalArgumentException(GrpcConnection.clientPathRequest(connection.protoRef()));
            }
            synchronized (entry) {
                if (entry.byName.isEmpty()) {
                    throw new IllegalArgumentException(GrpcConnection.clientPathRequest(connection.protoRef()));
                }
                return new DiscoveryEntry(List.copyOf(entry.methods), Map.copyOf(entry.byName));
            }
        }
        String key = discoveryKey(connection);
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
                    Проверь адрес/порт в headers (GRPC-Host, GRPC-Port, GRPC-Negotiation)
                    и что на целевом сервере включён server reflection, либо задай
                    GRPC-ProtoFile (имя файла на сервере либо путь к файлу на машине клиента —
                    тогда прочитай его и отправь содержимое через load_proto_file).
                    """
                    .formatted(connection.target());
        }
        if (discoverer instanceof DescriptorFileServiceDiscoverer) {
            return "Не удалось обнаружить методы из proto-файла " + connection.protoRef() + ".";
        }
        return "Не удалось обнаружить методы для " + connection.target() + ".";
    }

    private void requireTarget(GrpcConnection connection) {
        if (!connection.hasTarget()) {
            throw new IllegalArgumentException(GrpcConnection.NO_TARGET_MESSAGE);
        }
    }

    @Scheduled(fixedDelayString = "${grpc.sweep-interval:5m}")
    public void sweep() {
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
        } catch (Exception e) {
            log.warn("Connection sweep failed: {}", e.getMessage(), e);
        }
    }

    private static String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("Алгоритм SHA-256 недоступен", e);
        }
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
