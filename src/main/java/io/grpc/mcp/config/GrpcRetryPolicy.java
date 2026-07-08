package io.grpc.mcp.config;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.SimpleRetryPolicy;

public class GrpcRetryPolicy extends SimpleRetryPolicy {

    @Override
    public boolean canRetry(RetryContext context) {
        Throwable t = context.getLastThrowable();
        if (t instanceof StatusRuntimeException sre) {
            Status.Code code = sre.getStatus().getCode();
            if (code != Status.Code.UNAVAILABLE
                    && code != Status.Code.DEADLINE_EXCEEDED
                    && code != Status.Code.RESOURCE_EXHAUSTED) {
                return false;
            }
        } else if (t != null) {
            return false;
        }
        return super.canRetry(context);
    }
}
