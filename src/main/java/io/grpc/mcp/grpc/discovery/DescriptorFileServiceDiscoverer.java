package io.grpc.mcp.grpc.discovery;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DescriptorFileServiceDiscoverer implements ServiceDiscoverer {

    private final ProtoCompiler protoCompiler;
    private final DescriptorParser descriptorParser;

    @Getter
    private List<String> services = new ArrayList<>();
    @Getter
    private List<MethodMeta> methods = new ArrayList<>();

    public DescriptorFileServiceDiscoverer(ProtoCompiler protoCompiler, DescriptorParser descriptorParser) {
        this.protoCompiler = protoCompiler;
        this.descriptorParser = descriptorParser;
    }

    @Override
    public void discover() {
        byte[] data = protoCompiler.compile();
        DescriptorUtils.DiscoveredServices result = descriptorParser.parse(data);
        services = List.copyOf(result.serviceNames());
        methods = List.copyOf(result.methods());
        log.info("Discovered {} services with {} methods from proto file",
                services.size(), methods.size());
    }
}
