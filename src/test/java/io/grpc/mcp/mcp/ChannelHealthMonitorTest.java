package io.grpc.mcp.mcp;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChannelHealthMonitorTest {

    @Mock GrpcChannelManager channelManager;
    @Mock ServiceDiscoverer discovery;
    @Mock GrpcMethodRegistry registry;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ManagedChannel channel;

    private final ScheduledExecutorService realExecutor = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        realExecutor.shutdownNow();
    }

    private GrpcProperties props() {
        var p = new GrpcProperties();
        p.setHealthCheckInterval(Duration.ofSeconds(5));
        p.setReflectionTimeout(Duration.ofMillis(100));
        p.setProtoFile("");
        return p;
    }

    @Test
    void scheduleRegistersFixedDelay() {
        var executor = mock(ScheduledExecutorService.class);
        var props = props();
        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, executor);
        monitor.schedule();

        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void scheduledCheckReconnectsOnTransientFailure() {
        var props = props();
        props.setReflectionTimeout(Duration.ofMillis(50));
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.TRANSIENT_FAILURE);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.scheduledCheck();

        verify(channelManager).reconnect();
    }

    @Test
    void scheduledCheckReconnectsOnShutdown() {
        var props = props();
        props.setReflectionTimeout(Duration.ofMillis(50));
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.SHUTDOWN);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.scheduledCheck();

        verify(channelManager).reconnect();
    }

    @Test
    void scheduledCheckDoesNotReconnectWhenReady() {
        var props = props();
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.scheduledCheck();

        verify(channelManager, never()).reconnect();
    }

    @Test
    void scheduledCheckDoesNotReconnectOnIdle() {
        var props = props();
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.IDLE);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.scheduledCheck();

        verify(channelManager, never()).reconnect();
    }

    @Test
    void scheduledCheckDoesNotReconnectOnConnecting() {
        var props = props();
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.CONNECTING);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.scheduledCheck();

        verify(channelManager, never()).reconnect();
    }

    @Test
    void reconnectAndRediscoverRediscovery() throws Exception {
        var props = props();
        props.setReflectionTimeout(Duration.ofSeconds(1));
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(false)).thenReturn(ConnectivityState.READY);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.reconnectAndRediscover(ConnectivityState.TRANSIENT_FAILURE);

        verify(channelManager).reconnect();
        verify(eventPublisher).publishEvent(any(ChannelRecoveredEvent.class));
    }

    @Test
    void reconnectAndRediscoverSkippedOnDebounce() throws Exception {
        var props = props();
        props.setReflectionTimeout(Duration.ofMillis(50));
        when(channelManager.getChannel()).thenReturn(channel);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.reconnectAndRediscover(ConnectivityState.TRANSIENT_FAILURE);
        monitor.reconnectAndRediscover(ConnectivityState.TRANSIENT_FAILURE);

        verify(channelManager, times(1)).reconnect();
    }

    @Test
    void usesDefaultIntervalWhenPropertyIsNull() {
        var executor = mock(ScheduledExecutorService.class);
        var props = props();
        props.setHealthCheckInterval(null);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, executor);
        monitor.schedule();

        verify(executor).scheduleWithFixedDelay(any(), eq(0L), eq(1000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void handlesProtoFileModeSkipsRediscovery() throws Exception {
        var props = props();
        props.setProtoFile("/tmp/test.proto");
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(false)).thenReturn(ConnectivityState.READY);

        var monitor = new ChannelHealthMonitor(channelManager, discovery, registry, props, eventPublisher, realExecutor);
        monitor.schedule();
        monitor.reconnectAndRediscover(ConnectivityState.TRANSIENT_FAILURE);

        verify(channelManager).reconnect();
        verify(discovery, never()).rediscover(any());
        verify(eventPublisher).publishEvent(any(ChannelRecoveredEvent.class));
    }
}
