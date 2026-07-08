package io.grpc.mcp.config;

import io.grpc.ConnectivityState;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import io.grpc.mcp.mcp.ChannelHealthMonitor;

@RequiredArgsConstructor
@Slf4j
public class ChannelRecoveryRetryListener implements RetryListener {

    private final ChannelHealthMonitor channelHealthMonitor;

    @Override
    public <T, E extends Throwable> void onError(
            RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        if (throwable instanceof StatusRuntimeException sre
                && sre.getStatus().getCode() == Status.Code.UNAVAILABLE) {
            log.warn("gRPC UNAVAILABLE (attempt {}), reconnecting...", context.getRetryCount());
            channelHealthMonitor.reconnectAndRediscover(ConnectivityState.TRANSIENT_FAILURE);
        }
    }
}
