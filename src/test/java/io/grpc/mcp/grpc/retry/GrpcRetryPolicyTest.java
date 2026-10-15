package io.grpc.mcp.grpc.retry;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;
import io.grpc.mcp.config.GrpcRetryPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcRetryPolicyTest {

    private GrpcRetryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new GrpcRetryPolicy();
        policy.setMaxAttempts(3);
    }

    @Test
    void canRetry_unavailable() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.UNAVAILABLE, 1))).isTrue();
    }

    @Test
    void canRetry_deadlineExceeded() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.DEADLINE_EXCEEDED, 1))).isTrue();
    }

    @Test
    void canRetry_resourceExhausted() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.RESOURCE_EXHAUSTED, 1))).isTrue();
    }

    @Test
    void canRetry_invalidArgument() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.INVALID_ARGUMENT, 1))).isFalse();
    }

    @Test
    void canRetry_notFound() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.NOT_FOUND, 1))).isFalse();
    }

    @Test
    void canRetry_permissionDenied() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.PERMISSION_DENIED, 1))).isFalse();
    }

    @Test
    void canRetry_alreadyExists() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.ALREADY_EXISTS, 1))).isFalse();
    }

    @Test
    void canRetry_noThrowable() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(new SimpleContext(null, 1))).isTrue();
    }

    @Test
    void canRetry_nonGrpcException() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(new SimpleContext(new RuntimeException("boom"), 1))).isFalse();
    }

    @Test
    void canRetry_exceedsMaxAttempts() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.UNAVAILABLE, 4))).isFalse();
    }

    @Test
    void canRetry_ok() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.OK, 1))).isFalse();
    }

    @Test
    void canRetry_cancelled() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.CANCELLED, 1))).isFalse();
    }

    @Test
    void canRetry_internal() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.INTERNAL, 1))).isFalse();
    }

    @Test
    void canRetry_aborted() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.ABORTED, 1))).isFalse();
    }

    @Test
    void canRetry_unimplemented() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.UNIMPLEMENTED, 1))).isFalse();
    }

    @Test
    void canRetry_dataLoss() {
        // Arrange

        // Act

        // Assert
        assertThat(policy.canRetry(context(Status.Code.DATA_LOSS, 1))).isFalse();
    }

    private static RetryContext context(Status.Code code, int retryCount) {
        return new SimpleContext(Status.fromCode(code).asRuntimeException(), retryCount);
    }

    private record SimpleContext(Throwable lastThrowable, int retryCount) implements RetryContext {
        public void setAttribute(String name, Object value) { }
        public Object getAttribute(String name) { return null; }
        public Object removeAttribute(String name) { return null; }
        public boolean hasAttribute(String name) { return false; }
        public String[] attributeNames() { return new String[0]; }
        public void setRetryCount(int count) { }
        public int getRetryCount() { return retryCount; }
        public Throwable getLastThrowable() { return lastThrowable; }
        public boolean isExhaustedOnly() { return false; }
        public void setExhaustedOnly() { }
        public RetryContext getParent() { return null; }
    }
}
