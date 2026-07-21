package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import io.grpc.mcp.exceptions.DiscoveryException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public final class DescriptorParser {

    public DescriptorUtils.DiscoveredServices parse(byte[] descriptorData) {
        try {
            var fds = FileDescriptorSet.parseFrom(descriptorData);
            var protoList = fds.getFileList();

            if (protoList.isEmpty()) {
                throw new DiscoveryException("Descriptor set is empty (no files compiled)");
            }

            log.info("Loaded {} file descriptor protos from compiled descriptor set", protoList.size());
            return DescriptorUtils.collectServices(protoList);
        } catch (IOException e) {
            throw new DiscoveryException("Failed to parse compiled descriptor file", e);
        }
    }
}
