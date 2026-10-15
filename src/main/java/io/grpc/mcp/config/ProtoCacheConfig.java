package io.grpc.mcp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caffeine-backed Spring cache for the server-side proto cache (RAM only).
 * Entry lifecycle: expireAfterAccess (idle timeout) + maximumSize (LRU cap),
 * both from GrpcProperties. Stats are recorded so Caffeine cache metrics
 * (actuator) are exposed; the removal listener logs evictions.
 */
@Configuration
@Slf4j
public class ProtoCacheConfig {

    public static final String PROTO_CACHE = "protoCache";

    @Bean
    public CaffeineCacheManager protoCacheManager(GrpcProperties properties) {
        return cacheManager(properties);
    }

    /**
     * The native Caffeine cache backing the Spring CaffeineCache (same instance, so
     * metrics from recordStats stay consistent). Exposed separately so consumers use
     * the Caffeine API (get(key, loader), invalidate, cleanUp) without downcasting.
     */
    @Bean
    public Cache<Object, Object> protoCache(CaffeineCacheManager protoCacheManager) {
        return nativeCache(protoCacheManager);
    }

    public static Cache<Object, Object> nativeCache(CaffeineCacheManager manager) {
        return ((CaffeineCache) manager.getCache(PROTO_CACHE)).getNativeCache();
    }

    public static CaffeineCacheManager cacheManager(GrpcProperties properties) {
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .maximumSize(properties.getProtoCacheMaxEntries())
                .recordStats()
                .removalListener((key, value, cause) -> {
                    if (key instanceof String cacheKey) {
                        log.info("Evicted cached proto for {} ({})", cacheKey, cause);
                    }
                });
        if (properties.getProtoCacheIdleTimeout() != null) {
            caffeine = caffeine.expireAfterAccess(properties.getProtoCacheIdleTimeout());
        }
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(caffeine);
        return manager;
    }
}
