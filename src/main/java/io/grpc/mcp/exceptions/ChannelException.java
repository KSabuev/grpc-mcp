package io.grpc.mcp.exceptions;

public class ChannelException extends GrpcMcpException {

    public ChannelException(String message) {
        super(message);
    }

    public ChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
