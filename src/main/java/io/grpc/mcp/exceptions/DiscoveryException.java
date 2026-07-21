package io.grpc.mcp.exceptions;

public class DiscoveryException extends GrpcMcpException {

    public DiscoveryException(String message) {
        super(message);
    }

    public DiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
