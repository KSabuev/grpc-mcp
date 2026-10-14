package io.grpc.mcp.config;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.connection.ConnectionProvider;
import org.springframework.retry.RetryCallback;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GrpcRetryTemplateFactoryTest {

    @Mock ConnectionProvider connectionProvider;

    private GrpcProperties props;
    private GrpcRetryTemplateFactory factory;

    @BeforeEach
    void setUp() {
        props = new GrpcProperties();
        props.setMaxRetries(2);
        props.setBackoffInitial(Duration.ofMillis(1));
        props.setRetryBackoffMultiplier(1.5);
        props.setRetryMaxDelay(Duration.ofMillis(2));
        factory = new GrpcRetryTemplateFactory(props, connectionProvider);
    }

    @Test
    void forChannel_buildsWorkingTemplate() {
        // Arrange

        // Act
        var template = factory.forChannel("key1|PLAINTEXT|");

        // Assert
        assertThat(template).isNotNull();
    }

    @Test
    void unavailableRetriedAndChannelEvicted() {
        // Arrange
        var template = factory.forChannel("key1|PLAINTEXT|");
        var attempts = new AtomicInteger();

        // Act
        String result = template.execute(failsTwiceThenSucceeds(attempts));

        // Assert
        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        verify(connectionProvider, times(2)).evictChannel("key1|PLAINTEXT|");
    }

    @Test
    void nonUnavailableError_notRetried() {
        // Arrange
        var template = factory.forChannel("key1|PLAINTEXT|");
        var attempts = new AtomicInteger();

        // Act
        var thrown = catchThrowable(() -> template.execute(alwaysFails(attempts, Status.INVALID_ARGUMENT)));

        // Assert
        assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
        assertThat(attempts).hasValue(1);
        verify(connectionProvider, never()).evictChannel("key1|PLAINTEXT|");
    }

    private RetryCallback<String, StatusRuntimeException> failsTwiceThenSucceeds(AtomicInteger attempts) {
        return context -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new StatusRuntimeException(Status.UNAVAILABLE);
            }
            return "ok";
        };
    }

    private RetryCallback<String, StatusRuntimeException> alwaysFails(AtomicInteger attempts, Status status) {
        return context -> {
            attempts.incrementAndGet();
            throw new StatusRuntimeException(status);
        };
    }
}
