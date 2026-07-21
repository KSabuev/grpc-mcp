package io.grpc.mcp.exceptions;

public class MethodNotFoundException extends GrpcMcpException {

    public MethodNotFoundException(String message) {
        super(message);
    }
}
