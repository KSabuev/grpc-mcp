package io.grpc.mcp.mcp;

import io.grpc.ConnectivityState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

@Component
@RequiredArgsConstructor
@Slf4j
public class GrpcChannelHealthIndicator implements HealthIndicator {

    private final GrpcChannelManager channelManager;
    private final ServiceDiscoverer discovery;

    @Override
    public Health health() {
        ConnectivityState state = channelManager.getChannel().getState(true);

        var builder = state == ConnectivityState.READY
                ? Health.up()
                : Health.down();

        builder.withDetail("channelState", state.name());

        try {
            builder.withDetail("services", discovery.getServices().size());
            builder.withDetail("methods", discovery.getMethods().size());
        } catch (Exception e) {
            builder.withDetail("discoveryError", e.getMessage());
        }

        return builder.build();
    }
}
