package io.grpc.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.mcp.grpc.discovery.DescriptorFileServiceDiscoverer;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

@Configuration
@ConditionalOnExpression("!'${grpc.proto-file:}'.isEmpty()")
@Slf4j
public class FileBasedDiscoveryConfig {

    @Bean
    public ServiceDiscoverer fileBasedDiscoverer(GrpcProperties properties) {
        String protoFile = properties.getProtoFile();
        log.info("Discovery mode: FILE (proto file: {})", protoFile);
        DescriptorFileServiceDiscoverer discoverer = new DescriptorFileServiceDiscoverer(
                protoFile, properties.getProtocTimeout());
        discoverer.discover();
        return discoverer;
    }
}
