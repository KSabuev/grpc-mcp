package io.grpc.mcp.mcp;

import io.grpc.ConnectivityState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChannelHealthMonitor {

    private final GrpcChannelManager channelManager;
    private final ServiceDiscoverer discovery;
    private final GrpcMethodRegistry registry;
    private final GrpcProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduledExecutorService taskExecutor;

    private volatile Instant lastReconnect = Instant.EPOCH;
    private Duration healthCheckInterval;

    @PostConstruct
    public void schedule() {
        healthCheckInterval = properties.getHealthCheckInterval();
        if (healthCheckInterval == null || healthCheckInterval.isZero() || healthCheckInterval.isNegative()) {
            healthCheckInterval = Duration.ofSeconds(1);
        }
        taskExecutor.scheduleWithFixedDelay(
                this::scheduledCheck, 0, healthCheckInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void scheduledCheck() {
        ConnectivityState state = channelManager.getChannel().getState(true);
        if (state == ConnectivityState.TRANSIENT_FAILURE || state == ConnectivityState.SHUTDOWN) {
            log.warn("Channel in state {}, attempting recovery...", state);
            reconnectAndRediscover(state);
        }
    }

    public synchronized void reconnectAndRediscover(ConnectivityState previousState) {
        Duration debounce = healthCheckInterval.multipliedBy(3);
        Duration sinceLast = Duration.between(lastReconnect, Instant.now());
        if (sinceLast.compareTo(debounce) < 0) {
            log.debug("Skipping reconnect, only {} since last (debounce={})", sinceLast, debounce);
            return;
        }
        lastReconnect = Instant.now();

        channelManager.reconnect();
        ConnectivityState newState = channelManager.getChannel().getState(false);

        if (properties.getProtoFile() == null || properties.getProtoFile().isBlank()) {
            try {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    discovery.rediscover(channelManager.getChannel());
                    registry.updateMethods(discovery.getMethods());
                }, taskExecutor);

                Duration timeout = properties.getReflectionTimeout();
                if (timeout != null) {
                    future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    future.get();
                }
                log.info("Rediscovered {} methods after channel recovery", discovery.getMethods().size());
            } catch (TimeoutException e) {
                log.error("Rediscovery timed out after {}ms", properties.getReflectionTimeout().toMillis());
            } catch (Exception e) {
                log.error("Rediscovery failed after reconnect: {}", e.getMessage(), e);
            }
        }

        eventPublisher.publishEvent(new ChannelRecoveredEvent(previousState, newState));
    }
}
