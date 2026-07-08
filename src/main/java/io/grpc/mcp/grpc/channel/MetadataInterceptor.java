package io.grpc.mcp.grpc.channel;

import io.grpc.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MetadataInterceptor implements ClientInterceptor {

    private final Metadata extraHeaders;

    public MetadataInterceptor(Metadata extraHeaders) {
        this.extraHeaders = extraHeaders;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions opts, Channel next) {
        log.debug("MetadataInterceptor: intercepting call to {}", method.getFullMethodName());
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, opts)) {
            @Override
            public void start(Listener<RespT> listener, Metadata headers) {
                headers.merge(extraHeaders);
                log.debug("MetadataInterceptor: merged {} metadata entries", extraHeaders.keys().size());
                super.start(listener, headers);
            }
        };
    }
}
