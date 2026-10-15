package io.grpc.mcp.schema;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.grpc.mcp.config.GrpcProperties;
import io.grpc.mcp.mcp.ProtoSchemaConverter;
import io.grpc.mcp.mcp.WellKnownTypes;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoSchemaConverterTest {

    private ProtoSchemaConverter converter;
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
        WellKnownTypes wellKnownTypes = new WellKnownTypes();
        converter = new ProtoSchemaConverter(properties, wellKnownTypes);
    }

    private Descriptors.Descriptor buildSingleMessage(DescriptorProtos.DescriptorProto msg) {
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("test").addMessageType(msg).build();
        return buildFromFile(fdp).findMessageTypeByName(msg.getName());
    }

    private static Descriptors.FileDescriptor buildFromFile(DescriptorProtos.FileDescriptorProto fdp) {
        try {
            return Descriptors.FileDescriptor.buildFrom(fdp, new Descriptors.FileDescriptor[0]);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    @Test
    void emptyMessage() {
        // Arrange
        var desc = buildSingleMessage(
                DescriptorProtos.DescriptorProto.newBuilder().setName("EmptyMsg").build());

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("properties")).isEqualTo(Map.of());
        assertThat(schema.containsKey("required")).isFalse();
    }

    @Test
    void intField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithInt",
                field("count", DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "count").get("type"))
                .isEqualTo("integer");
    }

    @Test
    void stringField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithString",
                field("name", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "name").get("type"))
                .isEqualTo("string");
    }

    @Test
    void boolField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithBool",
                field("active", DescriptorProtos.FieldDescriptorProto.Type.TYPE_BOOL)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "active").get("type"))
                .isEqualTo("boolean");
    }

    @Test
    void doubleField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithDouble",
                field("price", DescriptorProtos.FieldDescriptorProto.Type.TYPE_DOUBLE)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "price").get("type"))
                .isEqualTo("number");
    }

    @Test
    void longField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithLong",
                field("id", DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "id").get("type"))
                .isEqualTo("integer");
    }

    @Test
    void floatField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithFloat",
                field("score", DescriptorProtos.FieldDescriptorProto.Type.TYPE_FLOAT)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "score").get("type"))
                .isEqualTo("number");
    }

    @Test
    void bytesField() {
        // Arrange
        var desc = buildSingleMessage(msg("WithBytes",
                field("data", DescriptorProtos.FieldDescriptorProto.Type.TYPE_BYTES)));

        // Act
        // Assert
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "data").get("type"))
                .isEqualTo("string");
        assertThat(field(converter.toJsonSchema(desc, Map.of(), Map.of()), "data"))
                .matches(m -> ((Map<String, Object>) m).get("contentEncoding").equals("base64"));
    }

    @Test
    void enumField() {
        // Arrange
        var enumType = DescriptorProtos.EnumDescriptorProto.newBuilder()
                .setName("Color")
                .addValue(eval("RED", 0))
                .addValue(eval("BLUE", 1))
                .build();
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithEnum")
                .addField(enumField("color", ".test.Color"))
                .build();
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("test")
                .addMessageType(msgProto).addEnumType(enumType).build();
        var desc = buildFromFile(fdp).findMessageTypeByName("WithEnum");

        // Act
        var schema = field(converter.toJsonSchema(desc, Map.of(), Map.of()), "color");

        // Assert
        assertThat(schema.get("type")).isEqualTo("string");
        assertThat(schema.get("enum")).asList().containsExactly("RED", "BLUE");
    }

    @Test
    void repeatedField() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithRepeated")
                .addField(repeatedField("tags", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var desc = buildSingleMessage(msgProto);

        // Act
        var schema = field(converter.toJsonSchema(desc, Map.of(), Map.of()), "tags");

        // Assert
        assertThat(schema.get("type")).isEqualTo("array");
        assertThat(((Map<String, Object>) schema.get("items")).get("type")).isEqualTo("string");
    }

    @Test
    void requiredField() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithRequired")
                .addField(requiredField("id", DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64))
                .build();
        var desc = buildSingleMessage(msgProto);

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        assertThat(schema.get("required")).asList().containsExactly("id");
    }

    @Test
    void optionalField_notInRequired() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithOptional")
                .addField(field("name", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var desc = buildSingleMessage(msgProto);

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        assertThat(schema.containsKey("required")).isTrue();
        assertThat(schema.get("required")).asList().contains("name");
    }

    @Test
    void withFieldComment() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithComment")
                .addField(field("email", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .build();
        var desc = buildSingleMessage(msgProto);

        // Act
        var schema = converter.toJsonSchema(desc, Map.of("email", "Primary email"), Map.of());

        // Assert
        assertThat(field(schema, "email").get("description")).asString()
                .startsWith("Primary email");
    }

    @Test
    void descriptionContainsProtoType() {
        // Arrange
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithTypeInfo")
                .addField(field("count", DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32))
                .build();
        var desc = buildSingleMessage(msgProto);

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        assertThat(field(schema, "count").get("description")).asString()
                .contains("INT32");
    }

    @Test
    void nestedMessageField() {
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
        var childFileDesc = buildFromFile(childFdp);

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
        var parentFileDesc = buildWithDeps(parentFdp, childFileDesc);
        var desc = parentFileDesc.findMessageTypeByName("Parent");

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        assertThat(field(schema, "child").get("type")).isEqualTo("object");
        assertThat(field((Map<String, Object>) field(schema, "child"), "val").get("type")).isEqualTo("string");
    }

    @Test
    void mapField() {
        // Arrange
        var entryMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("TagsEntry")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("key").setNumber(1).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("value").setNumber(2).setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING))
                .setOptions(DescriptorProtos.MessageOptions.newBuilder().setMapEntry(true))
                .build();
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithMap")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("tags").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                        .setTypeName(".test.TagsEntry")
                        .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED))
                .build();
        var fdp = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("test.proto").setPackage("test")
                .addMessageType(msgProto).addMessageType(entryMsg)
                .build();
        var desc = buildFromFile(fdp).findMessageTypeByName("WithMap");

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        var tagsField = field(schema, "tags");
        assertThat(tagsField.get("type")).isEqualTo("object");
        assertThat(tagsField).containsKey("additionalProperties");
    }

    @Test
    void oneofField() {
        // Arrange
        var oneofDecl = DescriptorProtos.OneofDescriptorProto.newBuilder().setName("choice").build();
        var msgProto = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("WithOneof")
                .addOneofDecl(oneofDecl)
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("text").setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .setOneofIndex(0))
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("number").setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
                        .setOneofIndex(0))
                .build();
        var desc = buildSingleMessage(msgProto);

        // Act
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        assertThat(field(schema, "text").get("type")).isEqualTo("string");
        assertThat(field(schema, "number").get("type")).isEqualTo("integer");
        assertThat(((String) field(schema, "text").get("description"))).contains("oneof");
        assertThat(schema).doesNotContainKey("required");
    }

    @Test
    void maxDepthTruncatesNesting() {
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
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        var midField = (Map<String, Object>) field(schema, "m");
        assertThat(midField.get("type")).isEqualTo("object");
        var innerField = (Map<String, Object>) field(midField, "i");
        assertThat(innerField.get("type")).isEqualTo("string");
    }

    @Test
    void circularReference_returnsStringDescription() {
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
        var schema = converter.toJsonSchema(desc, Map.of(), Map.of());

        // Assert
        var nextField = field(schema, "next");
        assertThat(nextField.get("type")).isEqualTo("object");
        var innerNext = field(nextField, "next");
        assertThat(innerNext.get("type")).isEqualTo("string");
    }

    private static Descriptors.FileDescriptor buildWithDeps(DescriptorProtos.FileDescriptorProto fdp,
                                                                     Descriptors.FileDescriptor... dependencies) {
        try {
            return Descriptors.FileDescriptor.buildFrom(fdp, dependencies);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new RuntimeException(e);
        }
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder field(String name,
                                                                         DescriptorProtos.FieldDescriptorProto.Type type) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder().setName(name).setNumber(1).setType(type);
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder enumField(String name, String typeName) {
        return DescriptorProtos.FieldDescriptorProto.newBuilder()
                .setName(name).setNumber(1)
                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM)
                .setTypeName(typeName);
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder repeatedField(String name,
                                                                                 DescriptorProtos.FieldDescriptorProto.Type type) {
        return field(name, type).setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REPEATED);
    }

    private static DescriptorProtos.FieldDescriptorProto.Builder requiredField(String name,
                                                                                 DescriptorProtos.FieldDescriptorProto.Type type) {
        return field(name, type).setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_REQUIRED);
    }

    private static DescriptorProtos.DescriptorProto msg(String name,
                                                          DescriptorProtos.FieldDescriptorProto.Builder... fields) {
        var b = DescriptorProtos.DescriptorProto.newBuilder().setName(name);
        for (var f : fields) b.addField(f);
        return b.build();
    }

    private static DescriptorProtos.EnumValueDescriptorProto.Builder eval(String name, int number) {
        return DescriptorProtos.EnumValueDescriptorProto.newBuilder().setName(name).setNumber(number);
    }
}
