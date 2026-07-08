package io.grpc.mcp.grpc.discovery;

import io.grpc.ManagedChannel;

import java.util.List;

public interface ServiceDiscoverer {
    List<String> getServices();
    List<MethodMeta> getMethods();
    void discover();
    default void rediscover(ManagedChannel channel) {}
}
