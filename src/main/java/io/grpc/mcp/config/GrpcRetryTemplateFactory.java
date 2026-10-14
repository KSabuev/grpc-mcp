package io.grpc.mcp.config;

import io.grpc.mcp.connection.ConnectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcRetryTemplateFactory {

    private final GrpcProperties properties;
    private final ConnectionProvider connectionProvider;

    public RetryTemplate forChannel(String channelKey) {
        int maxAttempts = properties.getMaxRetries() + 1;

        GrpcRetryPolicy retryPolicy = new GrpcRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(properties.getBackoffInitial().toMillis());
        backOff.setMultiplier(properties.getRetryBackoffMultiplier());
        backOff.setMaxInterval(properties.getRetryMaxDelay().toMillis());

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOff);
        template.registerListener(new ChannelRecoveryRetryListener(connectionProvider, channelKey));
        return template;
    }
}
