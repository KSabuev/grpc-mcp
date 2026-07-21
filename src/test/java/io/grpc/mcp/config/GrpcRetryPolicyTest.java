package io.grpc.mcp.config;

import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;

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
        assertThat(policy.canRetry(context(Status.Code.UNAVAILABLE, 1))).isTrue();
    }

    @Test
    void canRetry_deadlineExceeded() {
        assertThat(policy.canRetry(context(Status.Code.DEADLINE_EXCEEDED, 1))).isTrue();
    }

    @Test
    void canRetry_resourceExhausted() {
        assertThat(policy.canRetry(context(Status.Code.RESOURCE_EXHAUSTED, 1))).isTrue();
    }

    @Test
    void canRetry_invalidArgument() {
        assertThat(policy.canRetry(context(Status.Code.INVALID_ARGUMENT, 1))).isFalse();
    }

    @Test
    void canRetry_notFound() {
        assertThat(policy.canRetry(context(Status.Code.NOT_FOUND, 1))).isFalse();
    }

    @Test
    void canRetry_permissionDenied() {
        assertThat(policy.canRetry(context(Status.Code.PERMISSION_DENIED, 1))).isFalse();
    }

    @Test
    void canRetry_alreadyExists() {
        assertThat(policy.canRetry(context(Status.Code.ALREADY_EXISTS, 1))).isFalse();
    }

    @Test
    void canRetry_noThrowable() {
        assertThat(policy.canRetry(new SimpleContext(null, 1))).isTrue();
    }

    @Test
    void canRetry_nonGrpcException() {
        assertThat(policy.canRetry(new SimpleContext(new RuntimeException("boom"), 1))).isFalse();
    }

    @Test
    void canRetry_exceedsMaxAttempts() {
        assertThat(policy.canRetry(context(Status.Code.UNAVAILABLE, 4))).isFalse();
    }

    @Test
    void canRetry_ok() {
        assertThat(policy.canRetry(context(Status.Code.OK, 1))).isFalse();
    }

    @Test
    void canRetry_cancelled() {
        assertThat(policy.canRetry(context(Status.Code.CANCELLED, 1))).isFalse();
    }

    // === 2/3) Остальные коды (не входят в разрешённые) ===

    @Test
    void canRetry_internal() {
        assertThat(policy.canRetry(context(Status.Code.INTERNAL, 1))).isFalse();
    }

    @Test
    void canRetry_aborted() {
        assertThat(policy.canRetry(context(Status.Code.ABORTED, 1))).isFalse();
    }

    @Test
    void canRetry_unimplemented() {
        assertThat(policy.canRetry(context(Status.Code.UNIMPLEMENTED, 1))).isFalse();
    }

    @Test
    void canRetry_dataLoss() {
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
