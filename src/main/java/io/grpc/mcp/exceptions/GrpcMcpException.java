package io.grpc.mcp.exceptions;

public class GrpcMcpException extends RuntimeException {

    public GrpcMcpException(String message) {
        super(message);
    }

    public GrpcMcpException(String message, Throwable cause) {
        super(message, cause);
    }
}
