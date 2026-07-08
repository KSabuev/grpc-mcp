package io.grpc.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.mcp.grpc.channel.GrpcChannelManager;
import io.grpc.mcp.grpc.discovery.GrpcReflectionService;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

@Configuration
@ConditionalOnMissingBean(ServiceDiscoverer.class)
@Slf4j
public class ReflectionDiscoveryConfig {

    @Bean
    public ServiceDiscoverer reflectionDiscoverer(GrpcChannelManager channelManager,
                                                   GrpcProperties properties) {
        log.info("Discovery mode: REFLECTION (GRPC_PROTO_FILE not set)");
        GrpcReflectionService discoverer = new GrpcReflectionService(
                channelManager.getChannel(), "grpc-mcp", properties.getReflectionTimeout());
        discoverer.discover();
        return discoverer;
    }
}
