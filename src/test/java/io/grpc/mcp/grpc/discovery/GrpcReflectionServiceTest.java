package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.reflection.v1alpha.FileDescriptorResponse;
import io.grpc.reflection.v1alpha.ListServiceResponse;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.reflection.v1alpha.ServiceResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.mcp.exceptions.DiscoveryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrpcReflectionServiceTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) ManagedChannel channel;

    @Test
    void discoverPopulatesServicesAndMethods() {
        var svcResponse = ServiceResponse.newBuilder().setName("pkg.TestService").build();

        var listSvcResp = ServerReflectionResponse.newBuilder()
                .setListServicesResponse(
                        ListServiceResponse.newBuilder()
                                .addService(svcResponse)
                                .addService(ServiceResponse.newBuilder().setName("pkg.OtherService"))
                                .build())
                .build();

        var protoContent = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Req"))
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Res"))
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                        .setName("TestService")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                                .setName("DoIt")
                                .setInputType("pkg.Req").setOutputType("pkg.Res")))
                .build();

        var fileDescResp = ServerReflectionResponse.newBuilder()
                .setFileDescriptorResponse(
                        FileDescriptorResponse.newBuilder()
                                .addFileDescriptorProto(protoContent.toByteString())
                                .build())
                .build();

        try (var mockedCalls = mockStatic(ClientCalls.class)) {
            mockedCalls.when(() -> ClientCalls.blockingServerStreamingCall(
                            any(ManagedChannel.class), any(MethodDescriptor.class),
                            any(CallOptions.class), any(ServerReflectionRequest.class)))
                    .thenAnswer(invocation -> {
                        ServerReflectionRequest req = invocation.getArgument(3);
                        if (req.hasListServices()) {
                            return List.<ServerReflectionResponse>of(listSvcResp).iterator();
                        }
                        return List.<ServerReflectionResponse>of(fileDescResp).iterator();
                    });

            var service = new GrpcReflectionService(channel, "test-server", Duration.ofSeconds(5));
            service.discover();

            assertThat(service.getServices()).contains("pkg.TestService");
            assertThat(service.getMethods()).isNotEmpty();
            var method = service.getMethods().get(0);
            assertThat(method.serviceName()).isEqualTo("pkg.TestService");
            assertThat(method.methodName()).isEqualTo("DoIt");
            assertThat(method.fullMethodName()).isEqualTo("pkg.TestService/DoIt");
        }
    }

    @Test
    void handlesReflectionError() {
        try (var mockedCalls = mockStatic(ClientCalls.class)) {
            mockedCalls.when(() -> ClientCalls.blockingServerStreamingCall(
                            any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("reflection error"));

            var service = new GrpcReflectionService(channel, "test-server", Duration.ofSeconds(5));

            assertThatThrownBy(() -> service.discover())
                    .isInstanceOf(DiscoveryException.class)
                    .hasMessageContaining("Failed to discover gRPC methods");

            assertThat(service.getServices()).isEmpty();
            assertThat(service.getMethods()).isEmpty();
        }
    }

    @Test
    void rediscoverClearsAndReDiscovers() {
        try (var mockedCalls = mockStatic(ClientCalls.class)) {
            mockedCalls.when(() -> ClientCalls.blockingServerStreamingCall(
                            any(), any(), any(), any()))
                    .thenReturn(List.<ServerReflectionResponse>of().iterator());

            var service = new GrpcReflectionService(channel, "test-server", Duration.ofSeconds(5));
            service.rediscover(mock(ManagedChannel.class));

            assertThat(service.getServices()).isEmpty();
        }
    }

    @Test
    void handlesPartialFetchFailure() {
        var svcResponse = ServiceResponse.newBuilder().setName("pkg.GoodService").build();
        var badSvcResponse = ServiceResponse.newBuilder().setName("pkg.BadService").build();

        var listSvcResp = ServerReflectionResponse.newBuilder()
                .setListServicesResponse(
                        ListServiceResponse.newBuilder()
                                .addService(svcResponse)
                                .addService(badSvcResponse)
                                .build())
                .build();

        var goodProtoContent = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("good.proto").setPackage("pkg")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Req"))
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder().setName("Res"))
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder()
                        .setName("GoodService")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                                .setName("DoIt")
                                .setInputType("pkg.Req").setOutputType("pkg.Res")))
                .build();

        var goodFileDescResp = ServerReflectionResponse.newBuilder()
                .setFileDescriptorResponse(
                        FileDescriptorResponse.newBuilder()
                                .addFileDescriptorProto(goodProtoContent.toByteString())
                                .build())
                .build();

        try (var mockedCalls = mockStatic(ClientCalls.class)) {
            mockedCalls.when(() -> ClientCalls.blockingServerStreamingCall(
                            any(ManagedChannel.class), any(MethodDescriptor.class),
                            any(CallOptions.class), any(ServerReflectionRequest.class)))
                    .thenAnswer(invocation -> {
                        ServerReflectionRequest req = invocation.getArgument(3);
                        if (req.hasListServices()) {
                            return List.<ServerReflectionResponse>of(listSvcResp).iterator();
                        }
                        if (req.hasFileContainingSymbol()) {
                            String sym = req.getFileContainingSymbol();
                            if (sym.contains("BadService")) {
                                throw new RuntimeException("reflection error for " + sym);
                            }
                        }
                        return List.<ServerReflectionResponse>of(goodFileDescResp).iterator();
                    });

            var service = new GrpcReflectionService(channel, "test-server", Duration.ofSeconds(5));
            service.discover();

            assertThat(service.getServices()).contains("pkg.GoodService");
            assertThat(service.getMethods()).hasSize(1);
            assertThat(service.getMethods().get(0).serviceName()).isEqualTo("pkg.GoodService");
        }
    }


    @Test
    void discoverWithEmptyServiceList() {
        var emptyListSvcResp = ServerReflectionResponse.newBuilder()
                .setListServicesResponse(ListServiceResponse.newBuilder().build())
                .build();

        try (var mockedCalls = mockStatic(ClientCalls.class)) {
            mockedCalls.when(() -> ClientCalls.blockingServerStreamingCall(
                            any(), any(), any(), any()))
                    .thenReturn(List.<ServerReflectionResponse>of(emptyListSvcResp).iterator());

            var service = new GrpcReflectionService(channel, "test-server", Duration.ofSeconds(5));
            service.discover();

            assertThat(service.getServices()).isEmpty();
            assertThat(service.getMethods()).isEmpty();
        }
    }

    @Test
    void discoverWithNoServiceDescriptor() {
        var svcResponse = ServiceResponse.newBuilder().setName("pkg.NoDescService").build();
        var listSvcResp = ServerReflectionResponse.newBuilder()
                .setListServicesResponse(
                        ListServiceResponse.newBuilder().addService(svcResponse).build())
                .build();

        var fileDescResp = ServerReflectionResponse.newBuilder()
                .setFileDescriptorResponse(FileDescriptorResponse.newBuilder())
                .build();

        try (var mockedCalls = mockStatic(ClientCalls.class)) {
            mockedCalls.when(() -> ClientCalls.blockingServerStreamingCall(
                            any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        ServerReflectionRequest req = invocation.getArgument(3);
                        if (req.hasListServices()) {
                            return List.<ServerReflectionResponse>of(listSvcResp).iterator();
                        }
                        return List.<ServerReflectionResponse>of(fileDescResp).iterator();
                    });

            var service = new GrpcReflectionService(channel, "test-server", Duration.ofSeconds(5));
            service.discover();

            assertThat(service.getServices()).isEmpty();
            assertThat(service.getMethods()).isEmpty();
        }
    }
}
