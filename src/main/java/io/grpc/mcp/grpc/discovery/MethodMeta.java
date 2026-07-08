package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.Descriptors.Descriptor;
import lombok.Builder;

import java.util.Map;

@Builder
public record MethodMeta(
        String serviceName,
        String methodName,
        String fullMethodName,
        Descriptor inputDescriptor,
        Descriptor outputDescriptor,
        String methodDescription,
        String serviceDescription,
        String inputMessageDescription,
        Map<String, String> fieldComments,
        Map<String, String> enumValueComments,
        boolean serverStreaming,
        boolean clientStreaming
) {}
