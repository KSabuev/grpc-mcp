package io.grpc.mcp.mcp;

import io.grpc.ConnectivityState;
import io.grpc.mcp.connection.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class GrpcChannelHealthIndicator implements HealthIndicator {

    private final ConnectionProvider provider;

    @Override
    public Health health() {
        if (provider.isEmpty()) {
            return Health.up()
                    .withDetail("connections", 0)
                    .withDetail("note", "no client connections yet (connections are created on demand)")
                    .build();
        }

        Map<String, String> states = provider.channelStates();
        boolean anyFailing = states.values().stream()
                .anyMatch(state -> state.equals(ConnectivityState.TRANSIENT_FAILURE.name())
                        || state.equals(ConnectivityState.SHUTDOWN.name()));

        var builder = anyFailing ? Health.down() : Health.up();
        builder.withDetail("connections", states.size());
        builder.withDetail("channels", states);
        return builder.build();
    }
}
