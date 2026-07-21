package io.grpc.mcp.grpc.channel;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.config.GrpcProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrpcChannelManagerTest {

    @Mock MetadataInterceptor interceptor;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ManagedChannelBuilder<?> mockBuilder(ManagedChannel channel) {
        var builder = mock(ManagedChannelBuilder.class, Answers.RETURNS_SELF);
        doReturn(channel).when(builder).build();
        return builder;
    }

    private GrpcProperties defaultProps() {
        var p = new GrpcProperties();
        p.setAddress("localhost:8080");
        p.setCallTimeout(Duration.ofSeconds(10));
        p.setBackoffInitial(Duration.ofMillis(500));
        p.setHealthCheckInterval(Duration.ofSeconds(30));
        p.setMaxRetries(2);
        p.setKeepaliveTime(Duration.ofSeconds(10));
        p.setKeepaliveTimeout(Duration.ofSeconds(5));
        p.setRetryBackoffMultiplier(2.0);
        p.setRetryMaxDelay(Duration.ofSeconds(3));
        p.setProtocTimeout(Duration.ofSeconds(10));
        p.setReflectionTimeout(Duration.ofSeconds(5));
        p.setSchemaMaxDepth(8);
        p.setChannelShutdownTimeout(Duration.ofSeconds(5));
        return p;
    }

    @Test
    void initCreatesChannel() {
        var channel = mock(ManagedChannel.class);
        var mb = mockBuilder(channel);
        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget("localhost:8080"))
                    .thenReturn(mb);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.init();

            assertThat(mgr.getChannel()).isSameAs(channel);
        }
    }

    @Test
    void shutdownClosesChannel() {
        var channel = mock(ManagedChannel.class);
        doReturn(false).when(channel).isShutdown();
        var mb = mockBuilder(channel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString()))
                    .thenReturn(mb);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.init();
            mgr.shutdown();

            verify(channel).shutdown();
        }
    }

    @Test
    void reconnectRecreatesChannel() {
        var oldChannel = mock(ManagedChannel.class);
        var newChannel = mock(ManagedChannel.class);
        doReturn(false).when(oldChannel).isShutdown();
        var oldBuilder = mockBuilder(oldChannel);
        var newBuilder = mockBuilder(newChannel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString()))
                    .thenReturn(oldBuilder, newBuilder);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.init();
            mgr.reconnect();

            assertThat(mgr.getChannel()).isSameAs(newChannel);
            verify(oldChannel).shutdown();
        }
    }

    @Test
    void doesNotShutdownIfAlreadyShutDown() {
        var channel = mock(ManagedChannel.class);
        doReturn(true).when(channel).isShutdown();
        var mb = mockBuilder(channel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString()))
                    .thenReturn(mb);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.init();
            mgr.shutdown();

            verify(channel, never()).shutdown();
        }
    }

    @Test
    void reconnectHandlesNullOldChannel() {
        var newChannel = mock(ManagedChannel.class);
        var mb = mockBuilder(newChannel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString()))
                    .thenReturn(mb);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.reconnect();

            assertThat(mgr.getChannel()).isSameAs(newChannel);
        }
    }

    @Test
    void tlsChannel_createsNettyChannel() {
        var channel = mock(ManagedChannel.class);
        var builder = mock(NettyChannelBuilder.class, Answers.RETURNS_SELF);
        doReturn(channel).when(builder).build();
        var props = defaultProps();
        props.setNegotiationType("TLS");

        try (var mockedBuilder = mockStatic(NettyChannelBuilder.class)) {
            mockedBuilder.when(() -> NettyChannelBuilder.forTarget("localhost:8080"))
                    .thenReturn(builder);

            var mgr = new GrpcChannelManager(props, interceptor);
            mgr.init();

            assertThat(mgr.getChannel()).isSameAs(channel);
        }
    }

    @Test
    void reconnect_awaitTerminationTimeout_forceShutdown() throws Exception {
        var oldChannel = mock(ManagedChannel.class);
        var newChannel = mock(ManagedChannel.class);
        doReturn(false).when(oldChannel).isShutdown();
        when(oldChannel.awaitTermination(anyLong(), any())).thenReturn(false);

        var oldBuilder = mockBuilder(oldChannel);
        var newBuilder = mockBuilder(newChannel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString()))
                    .thenReturn(oldBuilder, newBuilder);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.init();
            mgr.reconnect();

            verify(oldChannel).shutdownNow();
        }
    }

    @Test
    void reconnect_interruptedDuringAwait_setsInterruptFlag() throws Exception {
        var oldChannel = mock(ManagedChannel.class);
        var newChannel = mock(ManagedChannel.class);
        doReturn(false).when(oldChannel).isShutdown();
        doThrow(new InterruptedException("interrupted"))
                .when(oldChannel).awaitTermination(anyLong(), any());

        var oldBuilder = mockBuilder(oldChannel);
        var newBuilder = mockBuilder(newChannel);

        try (var mockedBuilder = mockStatic(ManagedChannelBuilder.class)) {
            mockedBuilder.when(() -> ManagedChannelBuilder.forTarget(anyString()))
                    .thenReturn(oldBuilder, newBuilder);

            var mgr = new GrpcChannelManager(defaultProps(), interceptor);
            mgr.init();
            mgr.reconnect();

            verify(oldChannel).shutdownNow();
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
