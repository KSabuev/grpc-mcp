package io.grpc.mcp.grpc.channel;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.connection.GrpcConnection;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GrpcChannelFactoryTest {

    private GrpcProperties props;
    private GrpcChannelFactory factory;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ManagedChannelBuilder<?> mockBuilder(ManagedChannel channel) {
        var builder = mock(ManagedChannelBuilder.class, Answers.RETURNS_SELF);
        doReturn(channel).when(builder).build();
        return builder;
    }

    @BeforeEach
    void setUp() {
        props = new GrpcProperties();
        props.setCallTimeout(Duration.ofSeconds(10));
        props.setBackoffInitial(Duration.ofMillis(500));
        props.setMaxRetries(2);
        props.setKeepaliveTime(Duration.ofSeconds(10));
        props.setKeepaliveTimeout(Duration.ofSeconds(5));
        props.setRetryBackoffMultiplier(2.0);
        props.setRetryMaxDelay(Duration.ofSeconds(3));
        props.setProtocTimeout(Duration.ofSeconds(10));
        props.setReflectionTimeout(Duration.ofSeconds(5));
        props.setSchemaMaxDepth(8);
        factory = new GrpcChannelFactory(props);
    }

    @Test
    void plaintextChannel_created() {
        // Arrange
        var channel = mock(ManagedChannel.class);
        var mb = mockBuilder(channel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString())).thenReturn(mb);

            var connection = new GrpcConnection("localhost:50051", "PLAINTEXT", Map.of(), null);

            // Act
            var result = factory.createChannel(connection);

            // Assert
            assertThat(result).isSameAs(channel);
        }
    }

    @Test
    void tlsChannel_createdWithNetty() {
        // Arrange
        var channel = mock(ManagedChannel.class);
        var builder = mock(NettyChannelBuilder.class, Answers.RETURNS_SELF);
        doReturn(channel).when(builder).build();

        try (var mockedBuilder = mockStatic(NettyChannelBuilder.class)) {
            mockedBuilder.when(() -> NettyChannelBuilder.forTarget("localhost:50051")).thenReturn(builder);

            var connection = new GrpcConnection("localhost:50051", "TLS", Map.of(), null);

            // Act
            var result = factory.createChannel(connection);

            // Assert
            assertThat(result).isSameAs(channel);
        }
    }

    @Test
    void metadataAttachedToChannel() {
        // Arrange
        var channel = mock(ManagedChannel.class);
        var mb = mockBuilder(channel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString())).thenReturn(mb);

            var connection = new GrpcConnection("localhost:50051", "PLAINTEXT",
                    Map.of("x-user", "alice"), null);

            // Act
            factory.createChannel(connection);

            // Assert
            verify(mb).intercept(any(MetadataInterceptor.class));
        }
    }

    @Test
    void noMetadata_noInterceptor() {
        // Arrange
        var channel = mock(ManagedChannel.class);
        var mb = mockBuilder(channel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString())).thenReturn(mb);

            var connection = new GrpcConnection("localhost:50051", "PLAINTEXT", Map.of(), null);

            // Act
            factory.createChannel(connection);

            // Assert
            verify(mb, org.mockito.Mockito.never()).intercept(any(MetadataInterceptor.class));
        }
    }
}
