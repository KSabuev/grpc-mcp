package io.grpc.mcp.schema;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.mcp.ExampleGenerator;
import io.grpc.mcp.mcp.WellKnownTypes;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExampleGeneratorTest {

    private ExampleGenerator generator;
    private GrpcProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GrpcProperties();
        properties.setSchemaMaxDepth(8);
        properties.setCallTimeout(Duration.ofSeconds(10));
        properties.setBackoffInitial(Duration.ofMillis(500));
        properties.setMaxRetries(2);
        properties.setKeepaliveTime(Duration.ofSeconds(10));
        properties.setKeepaliveTimeout(Duration.ofSeconds(5));
        properties.setRetryBackoffMultiplier(2.0);
        properties.setRetryMaxDelay(Duration.ofSeconds(3));
        properties.setProtocTimeout(Duration.ofSeconds(10));
        properties.setReflectionTimeout(Duration.ofSeconds(5));
        properties.setAddress("localhost:8080");
        generator = new ExampleGenerator(properties, new WellKnownTypes());
    }

    @Test
    void generateExample_emptyMessage() {
        // Arrange
        var desc = buildMessage(DescriptorProtos.DescriptorProto.newBuilder().setName("EmptyMsg").build());

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example).isEmpty();
    }

    @Test
    void generateExample_intField() {
        // Arrange
        var desc = buildMessage(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithInt")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("count").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32))
                .build());

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("count")).isEqualTo(0);
    }

    @Test
    void generateExample_stringField() {
        // Arrange
        var desc = buildMessage(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithString")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("name").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .build());

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("name")).isEqualTo("name");
    }

    @Test
    void generateExample_boolField() {
        // Arrange
        var desc = buildMessage(DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithBool")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("active").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL))
                .build());

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("active")).isEqualTo(false);
    }

    @Test
    void generateExample_doubleField() {
        // Arrange
        var fieldProto = DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName("price").setNumber(1)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE)
                .build();
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithDouble")
                .addField(fieldProto)
                .build();
        var desc = buildMessage(msgProto);

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("price")).isEqualTo(0.0);
    }

    @Test
    void generateExample_enumField_usesFirstValue() {
        // Arrange
        var enumType = DescriptorProtos.EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName("RED").setNumber(0))
                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName("BLUE").setNumber(1))
                .build();
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithEnum")
                .addField(fieldBuilder("color", DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM)
                        .setTypeName(".test.Color"))
                .build();
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .addMessageType(msgProto)
                .addEnumType(enumType)
                .build();
        var desc = buildFromFile(fdp).findMessageTypeByName("WithEnum");

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("color")).isEqualTo("RED");
    }

    @Test
    void generateExample_repeatedField_returnsEmptyList() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithRepeated")
                .addField(fieldBuilder("tags", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .addMessageType(msgProto)
                .build();
        var desc = buildFromFile(fdp).findMessageTypeByName("WithRepeated");

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("tags")).isEqualTo(java.util.Collections.emptyList());
    }

    @Test
    void generateExample_mapField_returnsEmptyMap() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithMap")
                .addField(fieldBuilder("meta", DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.MetaEntry")
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        var entryProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("MetaEntry")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("key").setNumber(1).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("value").setNumber(2).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .setOptions(DescriptorProtos.MessageOptions.newBuilder()
                        .setMapEntry(true))
                .build();
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .addMessageType(msgProto)
                .addMessageType(entryProto)
                .build();
        var desc = buildFromFile(fdp).findMessageTypeByName("WithMap");

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("meta")).isEqualTo(java.util.Collections.emptyMap());
    }

    // === 2) Данные есть, граничные случаи ===

    @Test
    void generateExample_nestedMessage() {
        // Arrange
        var childMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Child")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("val").setNumber(1).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var childFdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("child.proto").setPackage("test")
                .addMessageType(childMsg)
                .build();
        var childFd = buildFromFile(childFdp);

        var parentMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Parent")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("child").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.Child"))
                .build();
        var parentFdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("parent.proto").setPackage("test")
                .addMessageType(parentMsg)
                .build();
        var parentFd = buildWithDeps(parentFdp, childFd);
        var desc = parentFd.findMessageTypeByName("Parent");

        // Act
        var example = generator.generateExample(desc);

        // Assert
        assertThat(example.get("child")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var child = (Map<String, Object>) example.get("child");
        assertThat(child.get("val")).isEqualTo("val");
    }

    @Test
    void generateExample_maxDepth_truncates() {
        // Arrange
        properties.setSchemaMaxDepth(1);
        var inner = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Inner").build();
        var mid = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Mid")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("i").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.Inner"))
                .build();
        var outer = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Outer")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("m").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.Mid"))
                .build();

        var innerFdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("inner.proto").setPackage("test")
                .addMessageType(inner).build();
        var innerFd = buildFromFile(innerFdp);

        var midFdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("mid.proto").setPackage("test")
                .addMessageType(mid).build();
        var midFd = buildWithDeps(midFdp, innerFd);

        var outerFdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("outer.proto").setPackage("test")
                .addMessageType(outer).build();
        var outerFd = buildWithDeps(outerFdp, midFd);
        var desc = outerFd.findMessageTypeByName("Outer");

        // Act
        var example = generator.generateExample(desc);

        // Assert
        @SuppressWarnings("unchecked")
        var midExample = (Map<String, Object>) example.get("m");
        assertThat(midExample.get("i")).isEqualTo("Message: test.Inner");
    }

    @Test
    void generateExample_circularReference_returnsString() {
        // Arrange
        var self = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("Node")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("next").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.Node"))
                .build();
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("test")
                .addMessageType(self)
                .build();
        var desc = buildFromFile(fdp).findMessageTypeByName("Node");

        // Act
        var example = generator.generateExample(desc);

        // Assert
        @SuppressWarnings("unchecked")
        var nextMap = (Map<String, Object>) example.get("next");
        assertThat(nextMap.get("next")).isEqualTo("Message: test.Node");
    }

    // null descriptor → NPE (no null guard in production code), intentionally not handled

    private static Descriptors.Descriptor buildMessage(DescriptorProtos.DescriptorProto msgProto) {
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto")
                .setPackage("test")
                .addMessageType(msgProto)
                .build();
        return buildFromFile(fdp).findMessageTypeByName(msgProto.getName());
    }

    private static Descriptors.FileDescriptor buildFromFile(DescriptorProtos.FileDescriptorProto fdp) {
        try {
            return Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[0]);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Descriptors.FileDescriptor buildWithDeps(DescriptorProtos.FileDescriptorProto fdp,
                                                                     Descriptors.FileDescriptor... dependencies) {
        try {
            return Descriptors.FileDescriptor.buildFrom(fdp, dependencies);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder fieldBuilder(String name,
                                                                               DescriptorProtos.FieldDescriptorProto.Type type) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(1)
                .setType(type);
    }
}
