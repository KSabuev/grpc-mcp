package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class GrpcReflectionService implements ServiceDiscoverer {

    private ManagedChannel channel;
    private final String serverName;
    private final Duration reflectionTimeout;
    private final MethodDescriptor<ServerReflectionRequest, ServerReflectionResponse> reflectionMethod =
            ServerReflectionGrpc.getServerReflectionInfoMethod();
    @Getter
    private final List<String> services = new ArrayList<>();
    @Getter
    private final List<MethodMeta> methods = new ArrayList<>();

    public GrpcReflectionService(ManagedChannel channel, String serverName, Duration reflectionTimeout) {
        this.channel = channel;
        this.serverName = serverName;
        this.reflectionTimeout = reflectionTimeout;
    }

    private CallOptions reflectionCallOptions() {
        return CallOptions.DEFAULT.withDeadlineAfter(
                reflectionTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void discover() {
        try {
            List<String> svcNames = listServices();
            log.info("Discovered {} gRPC services on server '{}'", svcNames.size(), serverName);
            List<FileDescriptorProto> allProtos = fetchFileDescriptors(svcNames);
            var result = DescriptorUtils.collectServices(allProtos);
            services.addAll(result.serviceNames());
            methods.addAll(result.methods());
            log.info("Discovered {} total methods on server '{}'", methods.size(), serverName);
        } catch (Exception e) {
            log.error("Failed to discover gRPC methods on server '{}': {}", serverName, e.getMessage(), e);
        }
    }

    private List<String> listServices() {
        List<String> svcs = new ArrayList<>();
        ServerReflectionRequest request = ServerReflectionRequest.newBuilder()
                .setListServices("")
                .build();

        Iterator<ServerReflectionResponse> iter = ClientCalls.blockingServerStreamingCall(
                channel, reflectionMethod, reflectionCallOptions(), request);
        while (iter.hasNext()) {
            ServerReflectionResponse response = iter.next();
            if (response.hasListServicesResponse()) {
                for (var svc : response.getListServicesResponse().getServiceList()) {
                    svcs.add(svc.getName());
                }
            }
        }
        return svcs;
    }

    private List<FileDescriptorProto> fetchFileDescriptors(List<String> svcNames) {
        List<FileDescriptorProto> allProtos = new ArrayList<>();
        for (String svcName : svcNames) {
            try {
                ServerReflectionRequest request = ServerReflectionRequest.newBuilder()
                        .setFileContainingSymbol(svcName)
                        .build();

                Iterator<ServerReflectionResponse> iter = ClientCalls.blockingServerStreamingCall(
                        channel, reflectionMethod, reflectionCallOptions(), request);
                while (iter.hasNext()) {
                    ServerReflectionResponse response = iter.next();
                    if (response.hasFileDescriptorResponse()) {
                        for (ByteString fb : response.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                            allProtos.add(FileDescriptorProto.parseFrom(fb));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to fetch descriptor for service {}: {}", svcName, e.getMessage(), e);
            }
        }
        return allProtos;
    }

    @Override
    public void rediscover(ManagedChannel newChannel) {
        this.channel = newChannel;
        services.clear();
        methods.clear();
        discover();
    }
}
