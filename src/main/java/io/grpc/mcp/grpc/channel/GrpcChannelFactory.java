package io.grpc.mcp.grpc.channel;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.connection.GrpcConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stateless factory: builds a ManagedChannel for a given per-client connection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcChannelFactory {

    private final GrpcProperties properties;

    public ManagedChannel createChannel(GrpcConnection connection) {
        ManagedChannel channel;
        if ("TLS".equalsIgnoreCase(connection.negotiation())) {
            channel = createTlsChannel(connection);
        } else {
            channel = createPlaintextChannel(connection);
        }
        log.info("Created {} gRPC channel to {}", connection.negotiation(), connection.target());
        return channel;
    }

    private <T extends ManagedChannelBuilder<?>> T withKeepalive(T builder) {
        builder.keepAliveTime(properties.getKeepaliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(properties.getKeepaliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(properties.isKeepaliveWithoutCalls());
        return builder;
    }

    private <T extends ManagedChannelBuilder<?>> T withMetadata(T builder, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return builder;
        }
        Metadata headers = new Metadata();
        metadata.forEach((k, v) ->
                headers.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v));
        log.debug("Attaching {} static metadata headers: {}", headers.keys().size(), headers.keys());
        builder.intercept(new MetadataInterceptor(headers));
        return builder;
    }

    private ManagedChannel createTlsChannel(GrpcConnection connection) {
        try {
            var builder = NettyChannelBuilder.forTarget(connection.target())
                    .sslContext(GrpcSslContexts.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build());
            return withMetadata(withKeepalive(builder), connection.metadata()).build();
        } catch (Exception e) {
            throw new RuntimeException("Не удалось создать TLS gRPC-канал до " + connection.target(), e);
        }
    }

    private ManagedChannel createPlaintextChannel(GrpcConnection connection) {
        var builder = ManagedChannelBuilder.forTarget(connection.target()).usePlaintext();
        return withMetadata(withKeepalive(builder), connection.metadata()).build();
    }
}
