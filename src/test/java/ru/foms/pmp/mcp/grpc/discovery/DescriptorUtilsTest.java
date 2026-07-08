package io.grpc.mcp.grpc.discovery;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DescriptorUtilsTest {

    private static FieldDescriptorProto.Builder field(String name, int num, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(num).setType(type);
    }

    @Test
    void buildFileDescriptors_emptyList_returnsEmpty() {
        assertThat(DescriptorUtils.buildFileDescriptors(List.of())).isEmpty();
    }

    @Test
    void buildFileDescriptors_singleProtoNoDependencies_resolves() {
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(DescriptorProto.newBuilder().setName("Msg"))
                .build();

        var result = DescriptorUtils.buildFileDescriptors(List.of(fdp));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).findMessageTypeByName("Msg")).isNotNull();
    }

    @Test
    void buildFileDescriptors_protoWithDependency_resolvesBoth() {
        var dep = FileDescriptorProto.newBuilder()
                .setName("dep.proto").setPackage("pkg")
                .addMessageType(DescriptorProto.newBuilder().setName("DepMsg"))
                .build();
        var main = FileDescriptorProto.newBuilder()
                .setName("main.proto").setPackage("pkg")
                .addDependency("dep.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("MainMsg"))
                .build();

        var result = DescriptorUtils.buildFileDescriptors(List.of(main, dep));

        assertThat(result).hasSize(2);
    }

    @Test
    void buildFileDescriptors_circularDependency_returnsEmpty() {
        var a = FileDescriptorProto.newBuilder()
                .setName("a.proto").setPackage("pkg")
                .addDependency("b.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("MsgA"))
                .build();
        var b = FileDescriptorProto.newBuilder()
                .setName("b.proto").setPackage("pkg")
                .addDependency("a.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("MsgB"))
                .build();

        var result = DescriptorUtils.buildFileDescriptors(List.of(a, b));

        assertThat(result).isEmpty();
    }

    @Test
    void buildFileDescriptors_unknownDependency_loggedAndSkipped() {
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addDependency("missing.proto")
                .addMessageType(DescriptorProto.newBuilder().setName("Msg"))
                .build();

        var result = DescriptorUtils.buildFileDescriptors(List.of(fdp));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).findMessageTypeByName("Msg")).isNotNull();
    }

    @Test
    void buildFileDescriptors_descriptorValidationException_removesInvalidProto() {
        var badMsg = DescriptorProto.newBuilder()
                .setName("BadMsg")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("ref").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("pkg.DoesNotExist"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("bad.proto").setPackage("pkg")
                .addMessageType(badMsg)
                .build();

        var result = DescriptorUtils.buildFileDescriptors(List.of(fdp));

        assertThat(result).isEmpty();
    }

    @Test
    void extractServiceComments_withLeadingComment_findsComment() {
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addService(ServiceDescriptorProto.newBuilder().setName("Svc"))
                .build();
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(6, 0))
                        .setLeadingComments("  Service description  "))
                .build();

        var svcComments = new java.util.HashMap<String, String>();
        DescriptorUtils.extractServiceComments(fdp, sci, svcComments);

        assertThat(svcComments).containsEntry("pkg.Svc", "Service description");
    }

    @Test
    void extractMethodComments_withTrailingComment_findsComment() {
        var svcWithMethod = ServiceDescriptorProto.newBuilder()
                .setName("Svc")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("DoIt")
                        .setInputType("pkg.Input")
                        .setOutputType("pkg.Output"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(DescriptorProto.newBuilder().setName("Input"))
                .addMessageType(DescriptorProto.newBuilder().setName("Output"))
                .addService(svcWithMethod)
                .build();
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(6, 0, 2, 0))
                        .setLeadingComments("")
                        .setTrailingComments("  trailing method comment  "))
                .build();

        var methodComments = new java.util.HashMap<String, String>();
        DescriptorUtils.extractMethodComments(fdp, sci, methodComments);

        assertThat(methodComments).containsEntry("pkg.Svc/DoIt", "trailing method comment");
    }

    @Test
    void extractMethodComments_bothCommentsBlank_omitsEntry() {
        var svcWithMethod = ServiceDescriptorProto.newBuilder()
                .setName("Svc")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("DoIt")
                        .setInputType("pkg.Input")
                        .setOutputType("pkg.Output"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(DescriptorProto.newBuilder().setName("Input"))
                .addMessageType(DescriptorProto.newBuilder().setName("Output"))
                .addService(svcWithMethod)
                .build();
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(6, 0, 2, 0))
                        .setLeadingComments("")
                        .setTrailingComments(""))
                .build();

        var methodComments = new java.util.HashMap<String, String>();
        DescriptorUtils.extractMethodComments(fdp, sci, methodComments);

        assertThat(methodComments).isEmpty();
    }

    @Test
    void collectMessageComments_findsAllMessages() {
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(DescriptorProto.newBuilder().setName("Msg1"))
                .addMessageType(DescriptorProto.newBuilder().setName("Msg2"))
                .build();
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 0))
                        .setLeadingComments("First message"))
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 1))
                        .setLeadingComments("Second message"))
                .build();

        var msgComments = new java.util.HashMap<String, String>();
        DescriptorUtils.collectMessageComments(fdp, sci, msgComments);

        assertThat(msgComments)
                .containsEntry("pkg.Msg1", "First message")
                .containsEntry("pkg.Msg2", "Second message");
    }

    // === collectFieldAndEnumComments ===

    @Test
    void collectFieldAndEnumComments_withFieldComment_findsFieldComment() {
        var msg = DescriptorProto.newBuilder()
                .setName("Msg")
                .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(msg)
                .build();
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 0, 2, 0))
                        .setLeadingComments("Field comment"))
                .build();

        var fieldComments = new java.util.HashMap<String, String>();
        var enumValueComments = new java.util.HashMap<String, String>();
        DescriptorUtils.collectFieldAndEnumComments(fdp, sci, fieldComments, enumValueComments);

        assertThat(fieldComments).containsEntry("pkg.Msg.name", "Field comment");
    }

    @Test
    void collectFieldAndEnumComments_withEnumValueComment_findsEnumComment() {
        var enumType = EnumDescriptorProto.newBuilder()
                .setName("Status")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("OK").setNumber(0))
                .addValue(EnumValueDescriptorProto.newBuilder().setName("ERROR").setNumber(1))
                .build();
        var msg = DescriptorProto.newBuilder()
                .setName("Msg")
                .addEnumType(enumType)
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(msg)
                .build();
        int enumTypeField = DescriptorProto.ENUM_TYPE_FIELD_NUMBER;
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 0, enumTypeField, 0, 2, 0))
                        .setLeadingComments("OK comment"))
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 0, enumTypeField, 0, 2, 1))
                        .setLeadingComments("ERROR comment"))
                .build();

        var fieldComments = new java.util.HashMap<String, String>();
        var enumValueComments = new java.util.HashMap<String, String>();
        DescriptorUtils.collectFieldAndEnumComments(fdp, sci, fieldComments, enumValueComments);

        assertThat(enumValueComments)
                .containsEntry("pkg.Msg.Status.OK", "OK comment")
                .containsEntry("pkg.Msg.Status.ERROR", "ERROR comment");
    }

    // === collectEnumValueComments (runtime Descriptor) ===

    @Test
    void collectEnumValueComments_enumField_collectsValues() throws Exception {
        var enumType = EnumDescriptorProto.newBuilder()
                .setName("Status")
                .addValue(EnumValueDescriptorProto.newBuilder().setName("OK").setNumber(0))
                .build();
        var msg = DescriptorProto.newBuilder()
                .setName("Msg")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("status").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("pkg.Status"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(msg)
                .addEnumType(enumType)
                .build();

        var fd = Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[0]);
        var descriptor = fd.findMessageTypeByName("Msg");

        var allEnumComments = Map.of("pkg.Status.OK", "OK value");
        var result = DescriptorUtils.collectEnumValueComments(descriptor, allEnumComments);

        assertThat(result).containsEntry("pkg.Status.OK", "OK value");
    }

    @Test
    void collectEnumValueComments_nestedMessageEnum_visitsRecursively() throws Exception {
        var innerMsg = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("status").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName("pkg.Status"))
                .build();
        var outerMsg = DescriptorProto.newBuilder()
                .setName("Outer")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("inner").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("pkg.Inner"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addEnumType(EnumDescriptorProto.newBuilder()
                        .setName("Status")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("ACTIVE").setNumber(0)))
                .addMessageType(outerMsg)
                .addMessageType(innerMsg)
                .build();

        var fd = Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[0]);
        var outer = fd.findMessageTypeByName("Outer");

        var allEnumComments = Map.of("pkg.Status.ACTIVE", "active value");
        var result = DescriptorUtils.collectEnumValueComments(outer, allEnumComments);

        assertThat(result).containsEntry("pkg.Status.ACTIVE", "active value");
    }

    @Test
    void collectEnumValueComments_circularReference_terminates() throws Exception {
        var msgA = DescriptorProto.newBuilder()
                .setName("MsgA")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("other").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("pkg.MsgB"))
                .build();
        var msgB = DescriptorProto.newBuilder()
                .setName("MsgB")
                .addField(FieldDescriptorProto.newBuilder()
                        .setName("other").setNumber(1)
                        .setType(FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName("pkg.MsgA"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(msgA)
                .addMessageType(msgB)
                .build();

        var fd = Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[0]);
        var descriptor = fd.findMessageTypeByName("MsgA");

        var result = DescriptorUtils.collectEnumValueComments(descriptor, Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void collectServices_withServiceAndMethod_populatesDiscoveredServices() {
        var inputMsg = DescriptorProto.newBuilder()
                .setName("Input")
                .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var outputMsg = DescriptorProto.newBuilder()
                .setName("Output")
                .addField(field("result", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var svcProto = ServiceDescriptorProto.newBuilder()
                .setName("Svc")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("DoIt")
                        .setInputType("pkg.Input")
                        .setOutputType("pkg.Output"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(inputMsg)
                .addMessageType(outputMsg)
                .addService(svcProto)
                .build();

        var result = DescriptorUtils.collectServices(List.of(fdp));

        assertThat(result.serviceNames()).containsExactly("pkg.Svc");
        assertThat(result.methods()).hasSize(1);
        var meta = result.methods().get(0);
        assertThat(meta.serviceName()).isEqualTo("pkg.Svc");
        assertThat(meta.methodName()).isEqualTo("DoIt");
        assertThat(meta.fullMethodName()).isEqualTo("pkg.Svc/DoIt");
        assertThat(meta.serverStreaming()).isFalse();
        assertThat(meta.clientStreaming()).isFalse();
        assertThat(meta.inputDescriptor()).isNotNull();
        assertThat(meta.outputDescriptor()).isNotNull();
    }

    @Test
    void collectServices_withComments_populatesDescriptions() {
        var inputMsg = DescriptorProto.newBuilder()
                .setName("Input")
                .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var outputMsg = DescriptorProto.newBuilder()
                .setName("Output")
                .addField(field("result", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var svcProto = ServiceDescriptorProto.newBuilder()
                .setName("Svc")
                .addMethod(MethodDescriptorProto.newBuilder()
                        .setName("DoIt")
                        .setInputType("pkg.Input")
                        .setOutputType("pkg.Output"))
                .build();
        var sci = SourceCodeInfo.newBuilder()
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(6, 0))
                        .setLeadingComments("Service desc"))
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(6, 0, 2, 0))
                        .setLeadingComments("Method desc"))
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 0))
                        .setLeadingComments("Input desc"))
                .addLocation(SourceCodeInfo.Location.newBuilder()
                        .addAllPath(List.of(4, 0, 2, 0))
                        .setLeadingComments("Field desc"))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(inputMsg)
                .addMessageType(outputMsg)
                .addService(svcProto)
                .setSourceCodeInfo(sci)
                .build();

        var result = DescriptorUtils.collectServices(List.of(fdp));

        var meta = result.methods().get(0);
        assertThat(meta.serviceDescription()).isEqualTo("Service desc");
        assertThat(meta.methodDescription()).isEqualTo("Method desc");
        assertThat(meta.inputMessageDescription()).isEqualTo("Input desc");
        assertThat(meta.fieldComments()).containsEntry("name", "Field desc");
    }

    @Test
    void collectServices_emptyProtoList_returnsEmpty() {
        var result = DescriptorUtils.collectServices(List.of());

        assertThat(result.serviceNames()).isEmpty();
        assertThat(result.methods()).isEmpty();
    }

    @Test
    void collectServices_protoWithoutServices_returnsEmpty() {
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(DescriptorProto.newBuilder().setName("Msg"))
                .build();

        var result = DescriptorUtils.collectServices(List.of(fdp));

        assertThat(result.serviceNames()).isEmpty();
        assertThat(result.methods()).isEmpty();
    }

    @Test
    void collectServices_multipleServices_collectsAll() {
        var inputMsg = DescriptorProto.newBuilder()
                .setName("Input")
                .addField(field("name", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var outputMsg = DescriptorProto.newBuilder()
                .setName("Output")
                .addField(field("result", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var fdp = FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("pkg")
                .addMessageType(inputMsg)
                .addMessageType(outputMsg)
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Svc1")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("M1").setInputType("pkg.Input").setOutputType("pkg.Output")))
                .addService(ServiceDescriptorProto.newBuilder()
                        .setName("Svc2")
                        .addMethod(MethodDescriptorProto.newBuilder()
                                .setName("M2").setInputType("pkg.Input").setOutputType("pkg.Output")))
                .build();

        var result = DescriptorUtils.collectServices(List.of(fdp));

        assertThat(result.serviceNames()).containsExactly("pkg.Svc1", "pkg.Svc2");
        assertThat(result.methods()).hasSize(2);
    }
}
