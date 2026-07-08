package io.grpc.mcp.mcp;

import io.grpc.ConnectivityState;

public record ChannelRecoveredEvent(ConnectivityState previousState, ConnectivityState newState) {
}
