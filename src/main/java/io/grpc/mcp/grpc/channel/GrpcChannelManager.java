package io.grpc.mcp.grpc.channel;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.grpc.mcp.config.GrpcProperties;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcChannelManager {

    private static final long CHANNEL_SHUTDOWN_TIMEOUT_MS = 1000;

    private final GrpcProperties properties;
    private final MetadataInterceptor interceptor;

    @Getter
    private volatile ManagedChannel channel;

    @PostConstruct
    public void init() {
        this.channel = createChannel();
        log.info("gRPC channel manager initialized, target={}, negotiation={}",
                properties.getAddress(), properties.getNegotiationType());
    }

    @PreDestroy
    public void shutdown() {
        ManagedChannel toClose = channel;
        if (toClose != null && !toClose.isShutdown()) {
            log.info("Shutting down gRPC channel to {}", properties.getAddress());
            toClose.shutdown();
        }
    }

    private <T extends ManagedChannelBuilder<?>> T addMetadata(T builder) {
        builder.intercept(interceptor);
        return builder;
    }

    private <T extends ManagedChannelBuilder<?>> T addKeepalive(T builder) {
        builder.keepAliveTime(properties.getKeepaliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(properties.getKeepaliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(properties.isKeepaliveWithoutCalls());
        return builder;
    }

    private ManagedChannel createTlsChannel() {
        try {
            var ch = addKeepalive(addMetadata(NettyChannelBuilder.forTarget(properties.getAddress())
                    .sslContext(GrpcSslContexts.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build()))).build();
            log.info("Created TLS gRPC channel to {}", properties.getAddress());
            return ch;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create TLS gRPC channel to " + properties.getAddress(), e);
        }
    }

    private ManagedChannel createChannel() {
        if ("TLS".equalsIgnoreCase(properties.getNegotiationType())) {
            return createTlsChannel();
        }
        ManagedChannel ch = addKeepalive(addMetadata(
                ManagedChannelBuilder.forTarget(properties.getAddress()).usePlaintext())).build();
        log.info("Created plaintext gRPC channel to {}", properties.getAddress());
        return ch;
    }

    public synchronized void reconnect() {
        ManagedChannel old = channel;
        channel = createChannel();
        if (old != null && !old.isShutdown()) {
            log.info("Shutting down old gRPC channel to {}", properties.getAddress());
            old.shutdown();
            try {
                if (!old.awaitTermination(CHANNEL_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    old.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("Channel shutdown interrupted for {}", properties.getAddress());
                old.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
