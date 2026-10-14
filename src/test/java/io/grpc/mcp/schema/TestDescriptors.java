package io.grpc.mcp.schema;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;

public class TestDescriptors {

    public static Descriptors.FileDescriptor file(String name, String pkg,
                                                   DescriptorProtos.DescriptorProto... messages) {
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName(name).setPackage(pkg);
        for (var m : messages) fdp.addMessageType(m);
        for (var m : messages) {
            if (m.getOptions().getMapEntry()) {
                fdp.addMessageType(m);
            }
        }
        try {
            return Descriptors.FileDescriptor.buildFrom(fdp.build(), new Descriptors.FileDescriptor[0]);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    public static DescriptorProtos.FieldDescriptorProto.Builder field(String name, int num,
                                                                       DescriptorProtos.FieldDescriptorProto.Type type) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(num).setType(type);
    }

    public static DescriptorProtos.FieldDescriptorProto.Builder msgField(String name, int num, String typeName) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(num)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                .setTypeName(typeName);
    }

    public static Descriptors.Descriptor simpleMessage(String name, String pkg,
                                                        DescriptorProtos.FieldDescriptorProto.Builder... fields) {
        var b = DescriptorProtos.DescriptorProto.newBuilder().setName(name);
        for (var f : fields) b.addField(f);
        var fd = file(name + ".proto", pkg, b.build());
        return fd.findMessageTypeByName(name);
    }
}
