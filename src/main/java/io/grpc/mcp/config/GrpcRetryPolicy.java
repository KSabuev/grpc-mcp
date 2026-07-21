package io.grpc.mcp.config;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Set;

public class GrpcRetryPolicy extends SimpleRetryPolicy {

    private static final Set<Status.Code> RETRYABLE_CODES = Set.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.RESOURCE_EXHAUSTED
    );

    @Override
    public boolean canRetry(RetryContext context) {
        Throwable t = context.getLastThrowable();
        if (t instanceof StatusRuntimeException sre) {
            if (!RETRYABLE_CODES.contains(sre.getStatus().getCode())) {
                return false;
            }
        } else if (t != null) {
            return false;
        }
        return super.canRetry(context);
    }
}
