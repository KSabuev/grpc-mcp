package io.grpc.mcp.grpc.channel;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataInterceptorTest {

    @Mock Channel next;
    @Mock ClientCall.Listener<String> listener;
    @Mock ClientCall<String, String> delegateCall;
    @Mock MethodDescriptor<String, String> method;

    @Test
    void mergesExtraHeadersIntoCall() {
        // Arrange
        var extra = new Metadata();
        extra.put(Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER), "secret123");
        extra.put(Metadata.Key.of("X-Trace-Id", Metadata.ASCII_STRING_MARSHALLER), "trace-abc");

        when(next.newCall(method, CallOptions.DEFAULT)).thenReturn(delegateCall);

        var interceptor = new MetadataInterceptor(extra);
        var headersCaptor = ArgumentCaptor.forClass(Metadata.class);

        // Act
        interceptor.interceptCall(method, CallOptions.DEFAULT, next)
                .start(listener, new Metadata());

        // Assert
        verify(delegateCall).start(any(), headersCaptor.capture());
        var actual = headersCaptor.getValue();

        assertThat(actual.get(Metadata.Key.of("X-API-Key", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("secret123");
        assertThat(actual.get(Metadata.Key.of("X-Trace-Id", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("trace-abc");
    }

    @Test
    void delegatesToNextCall() {
        // Arrange
        when(next.newCall(method, CallOptions.DEFAULT)).thenReturn(delegateCall);

        var interceptor = new MetadataInterceptor(new Metadata());

        // Act
        interceptor.interceptCall(method, CallOptions.DEFAULT, next)
                .start(listener, new Metadata());

        // Assert
        verify(delegateCall).start(any(), any());
    }

    @Test
    void delegatesCallMethods() {
        // Arrange
        when(next.newCall(method, CallOptions.DEFAULT)).thenReturn(delegateCall);

        var extra = new Metadata();
        extra.put(Metadata.Key.of("X-Key", Metadata.ASCII_STRING_MARSHALLER), "val");
        var interceptor = new MetadataInterceptor(extra);

        // Act
        interceptor.interceptCall(method, CallOptions.DEFAULT, next)
                .cancel("test", null);

        // Assert
        verify(delegateCall).cancel("test", null);
    }

    // === 2) Данные есть, ошибочные ===

    @Test
    void mergesBinaryMetadata() {
        // Arrange
        var extra = new Metadata();
        var binKey = Metadata.Key.of("trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
        extra.put(binKey, new byte[]{0, 1, 2});

        when(next.newCall(method, CallOptions.DEFAULT)).thenReturn(delegateCall);

        var interceptor = new MetadataInterceptor(extra);

        // Act
        interceptor.interceptCall(method, CallOptions.DEFAULT, next)
                .start(listener, new Metadata());

        // Assert
        verify(delegateCall).start(any(), any());
    }

    @Test
    void extraHeadersOverrideExisting() {
        // Arrange
        var extra = new Metadata();
        extra.put(Metadata.Key.of("X-Key", Metadata.ASCII_STRING_MARSHALLER), "override");

        when(next.newCall(method, CallOptions.DEFAULT)).thenReturn(delegateCall);

        var interceptor = new MetadataInterceptor(extra);
        var existingHeaders = new Metadata();
        existingHeaders.put(Metadata.Key.of("X-Key", Metadata.ASCII_STRING_MARSHALLER), "original");

        var headersCaptor = ArgumentCaptor.forClass(Metadata.class);

        // Act
        interceptor.interceptCall(method, CallOptions.DEFAULT, next)
                .start(listener, existingHeaders);

        // Assert
        verify(delegateCall).start(any(), headersCaptor.capture());
        var actual = headersCaptor.getValue();
        assertThat(actual.get(Metadata.Key.of("X-Key", Metadata.ASCII_STRING_MARSHALLER)))
                .isEqualTo("override");
    }
}
