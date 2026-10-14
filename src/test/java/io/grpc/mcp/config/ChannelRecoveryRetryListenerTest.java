package io.grpc.mcp.config;

import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import io.grpc.mcp.connection.ConnectionProvider;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChannelRecoveryRetryListenerTest {

    @Mock ConnectionProvider connectionProvider;
    @Mock RetryContext context;
    @Mock RetryCallback<?, ?> callback;

    @Test
    void onError_unavailable_evictsChannel() {
        // Arrange
        var listener = new ChannelRecoveryRetryListener(connectionProvider, "host:50051|PLAINTEXT");

        // Act
        listener.onError(context, callback, Status.UNAVAILABLE.asRuntimeException());

        // Assert
        verify(connectionProvider).evictChannel("host:50051|PLAINTEXT");
    }

    @Test
    void onError_otherStatus_doesNothing() {
        // Arrange
        var listener = new ChannelRecoveryRetryListener(connectionProvider, "host:50051|PLAINTEXT");

        // Act
        listener.onError(context, callback, Status.INTERNAL.asRuntimeException());

        // Assert
        verifyNoInteractions(connectionProvider);
    }

    @Test
    void onError_nonGrpcException_doesNothing() {
        // Arrange
        var listener = new ChannelRecoveryRetryListener(connectionProvider, "host:50051|PLAINTEXT");

        // Act
        listener.onError(context, callback, new RuntimeException("test"));

        // Assert
        verifyNoInteractions(connectionProvider);
    }
}
