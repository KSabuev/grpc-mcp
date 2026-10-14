package io.grpc.mcp.config;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.mcp.connection.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;

@RequiredArgsConstructor
@Slf4j
public class ChannelRecoveryRetryListener implements RetryListener {

    private final ConnectionProvider connectionProvider;
    private final String channelKey;

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (throwable instanceof StatusRuntimeException sre
                && sre.getStatus().getCode() == Status.Code.UNAVAILABLE) {
            log.warn("gRPC UNAVAILABLE (attempt {}), evicting channel {} for rebuild",
                    context.getRetryCount(), channelKey);
            connectionProvider.evictChannel(channelKey);
        }
    }
}
