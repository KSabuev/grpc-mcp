package io.grpc.mcp.grpc.channel;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.exceptions.ChannelException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcChannelManager {

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
            var sslBuilder = GrpcSslContexts.forClient();

            if (properties.isTlsInsecure()) {
                log.warn("TLS configured with InsecureTrustManager - NO certificate verification");
                sslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            } else if (properties.getTlsTrustCert() != null && !properties.getTlsTrustCert().isBlank()) {
                File trustCertFile = new File(properties.getTlsTrustCert());
                if (!trustCertFile.exists()) {
                    throw new ChannelException("TLS trust certificate not found: " + properties.getTlsTrustCert());
                }
                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                trustStore.load(null, null);
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                try (var fis = new java.io.FileInputStream(trustCertFile)) {
                    java.security.cert.Certificate cert = cf.generateCertificate(fis);
                    trustStore.setCertificateEntry("grpc-ca", cert);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                sslBuilder.trustManager(tmf);
                log.info("TLS configured with custom trust certificate: {}", properties.getTlsTrustCert());
            } else {
                log.info("TLS configured with system default truststore");
            }

            var ch = addKeepalive(addMetadata(
                    NettyChannelBuilder.forTarget(properties.getAddress())
                            .sslContext(sslBuilder.build()))).build();
            log.info("Created TLS gRPC channel to {}", properties.getAddress());
            return ch;
        } catch (ChannelException e) {
            throw e;
        } catch (Exception e) {
            throw new ChannelException("Failed to create TLS gRPC channel to " + properties.getAddress(), e);
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
                long timeoutMs = properties.getChannelShutdownTimeout().toMillis();
                if (!old.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
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
