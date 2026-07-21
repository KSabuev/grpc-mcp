package io.grpc.mcp.config;

import io.grpc.ConnectivityState;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import io.grpc.mcp.mcp.ChannelHealthMonitor;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChannelRecoveryRetryListenerTest {

    @Mock ChannelHealthMonitor channelHealthMonitor;
    @Mock RetryContext context;
    @Mock RetryCallback<?, ?> callback;

    @Test
    void onError_unavailable_callsReconnect() {
        var listener = new ChannelRecoveryRetryListener(channelHealthMonitor);
        listener.onError(context, callback, Status.UNAVAILABLE.asRuntimeException());

        verify(channelHealthMonitor).reconnectAndRediscover(ConnectivityState.TRANSIENT_FAILURE);
    }

    @Test
    void onError_otherStatus_doesNothing() {
        var listener = new ChannelRecoveryRetryListener(channelHealthMonitor);
        listener.onError(context, callback, Status.INTERNAL.asRuntimeException());

        verifyNoInteractions(channelHealthMonitor);
    }

    @Test
    void onError_nonGrpcException_doesNothing() {
        var listener = new ChannelRecoveryRetryListener(channelHealthMonitor);
        listener.onError(context, callback, new RuntimeException("test"));

        verifyNoInteractions(channelHealthMonitor);
    }
}
