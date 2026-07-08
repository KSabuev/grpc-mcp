package io.grpc.mcp.mcp;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrpcChannelHealthIndicatorTest {

    @Mock GrpcChannelManager channelManager;
    @Mock ServiceDiscoverer discovery;
    @Mock ManagedChannel channel;

    @Test
    void upWhenChannelIsReady() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);
        when(discovery.getServices()).thenReturn(List.of("Svc1"));
        when(discovery.getMethods()).thenReturn(List.of());

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat((String) health.getDetails().get("channelState")).isEqualTo("READY");
    }

    @Test
    void downWhenChannelIsNotReady() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.TRANSIENT_FAILURE);
        when(discovery.getServices()).thenReturn(List.of());
        when(discovery.getMethods()).thenReturn(List.of());

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat((String) health.getDetails().get("channelState")).isEqualTo("TRANSIENT_FAILURE");
    }

    @Test
    void includesServiceAndMethodCounts() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);
        when(discovery.getServices()).thenReturn(List.of("Svc1", "Svc2"));
        when(discovery.getMethods()).thenReturn(List.of(
                MethodMeta.builder().fullMethodName("x/y").build(),
                MethodMeta.builder().fullMethodName("a/b").build(),
                MethodMeta.builder().fullMethodName("c/d").build()
        ));

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getDetails().get("services")).isEqualTo(2);
        assertThat(health.getDetails().get("methods")).isEqualTo(3);
    }

    @Test
    void handlesDiscoveryException() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.READY);
        when(discovery.getServices()).thenThrow(new RuntimeException("timeout"));

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getDetails()).containsKey("discoveryError");
    }

    @Test
    void idleState_returnsDown() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.IDLE);
        when(discovery.getServices()).thenReturn(List.of());
        when(discovery.getMethods()).thenReturn(List.of());

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void connectingState_returnsDown() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.CONNECTING);
        when(discovery.getServices()).thenReturn(List.of());
        when(discovery.getMethods()).thenReturn(List.of());

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void shutdownState_returnsDown() {
        when(channelManager.getChannel()).thenReturn(channel);
        when(channel.getState(true)).thenReturn(ConnectivityState.SHUTDOWN);
        when(discovery.getServices()).thenReturn(List.of());
        when(discovery.getMethods()).thenReturn(List.of());

        var indicator = new GrpcChannelHealthIndicator(channelManager, discovery);
        var health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }
}
