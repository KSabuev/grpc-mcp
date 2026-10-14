package io.grpc.mcp.mcp;

import io.grpc.mcp.connection.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrpcChannelHealthIndicatorTest {

    @Mock ConnectionProvider provider;

    @Test
    void noConnections_up() {
        // Arrange
        when(provider.isEmpty()).thenReturn(true);

        var indicator = new GrpcChannelHealthIndicator(provider);

        // Act
        var health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("connections")).isEqualTo(0);
    }

    @Test
    void upWhenAllChannelsReady() {
        // Arrange
        when(provider.isEmpty()).thenReturn(false);
        when(provider.channelStates()).thenReturn(new LinkedHashMap<>(
                Map.of("host1:50051|PLAINTEXT", "READY", "host2:50051|TLS", "READY")));

        var indicator = new GrpcChannelHealthIndicator(provider);

        // Act
        var health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails().get("connections")).isEqualTo(2);
    }

    @Test
    void downWhenAnyChannelFailing() {
        // Arrange
        when(provider.isEmpty()).thenReturn(false);
        when(provider.channelStates()).thenReturn(new LinkedHashMap<>(
                Map.of("host1:50051|PLAINTEXT", "READY", "host2:50051|TLS", "TRANSIENT_FAILURE")));

        var indicator = new GrpcChannelHealthIndicator(provider);

        // Act
        var health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void downWhenChannelShutdown() {
        // Arrange
        when(provider.isEmpty()).thenReturn(false);
        when(provider.channelStates()).thenReturn(new LinkedHashMap<>(
                Map.of("host1:50051|PLAINTEXT", "SHUTDOWN")));

        var indicator = new GrpcChannelHealthIndicator(provider);

        // Act
        var health = indicator.health();

        // Assert
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }
}
