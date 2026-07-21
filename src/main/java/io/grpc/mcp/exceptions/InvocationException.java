package io.grpc.mcp.exceptions;

public class InvocationException extends GrpcMcpException {

    public InvocationException(String message) {
        super(message);
    }

    public InvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
