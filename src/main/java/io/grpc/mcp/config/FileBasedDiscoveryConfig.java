package io.grpc.mcp.config;

import io.grpc.mcp.grpc.discovery.DescriptorFileServiceDiscoverer;
import io.grpc.mcp.grpc.discovery.DescriptorParser;
import io.grpc.mcp.grpc.discovery.ProtoCompiler;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression("!'${grpc.proto-file:}'.isEmpty()")
@Slf4j
public class FileBasedDiscoveryConfig {

    @Bean
    public ProtoCompiler protoCompiler(GrpcProperties properties) {
        return new ProtoCompiler(properties.getProtoFile(), properties.getProtocTimeout());
    }

    @Bean
    public DescriptorParser descriptorParser() {
        return new DescriptorParser();
    }

    @Bean
    public ServiceDiscoverer fileBasedDiscoverer(ProtoCompiler protoCompiler, DescriptorParser descriptorParser) {
        log.info("Discovery mode: FILE");
        DescriptorFileServiceDiscoverer discoverer = new DescriptorFileServiceDiscoverer(
                protoCompiler, descriptorParser);
        discoverer.discover();
        return discoverer;
    }
}
