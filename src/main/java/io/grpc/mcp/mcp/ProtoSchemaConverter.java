package io.grpc.mcp.mcp;

import com.google.protobuf.Descriptors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import io.grpc.mcp.config.GrpcProperties;

import java.util.*;

import static io.grpc.mcp.mcp.SchemaMap.entry;

@Component
@RequiredArgsConstructor
public class ProtoSchemaConverter {

    private final GrpcProperties properties;
    private final WellKnownTypes wellKnownTypes;

    public Map<String, Object> toJsonSchema(Descriptors.Descriptor descriptor,
                                             Map<String, String> fieldComments,
                                             Map<String, String> enumValueComments) {
        return toJsonSchema(descriptor, new HashSet<>(), 0, fieldComments, enumValueComments);
    }

    private static boolean isOptionalField(Descriptors.FieldDescriptor field) {
        return field.isRepeated()
                || field.isMapField()
                || field.getContainingOneof() != null;
    }

    private Map<String, Object> toJsonSchema(Descriptors.Descriptor descriptor, Set<String> visited, int depth,
                                               Map<String, String> fieldComments,
                                               Map<String, String> enumValueComments) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            if (!isOptionalField(field)) {
                required.add(field.getName());
            }
        }

        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            Map<String, Object> fieldSchema = convertField(field, visited, depth, fieldComments, enumValueComments);
            addFieldDescription(fieldSchema, field, fieldComments, enumValueComments);
            properties.put(field.getName(), fieldSchema);
        }

        Map<String, Object> schema = entry("object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private Map<String, Object> convertField(Descriptors.FieldDescriptor field, Set<String> visited, int depth,
                                              Map<String, String> fieldComments,
                                              Map<String, String> enumValueComments) {
        if (field.isMapField()) {
            Map<String, Object> mapSchema = entry("object");
            mapSchema.put("additionalProperties", convertSingleField(field, visited, depth, fieldComments, enumValueComments));
            return mapSchema;
        }

        if (field.isRepeated()) {
            Map<String, Object> items = convertSingleField(field, visited, depth, fieldComments, enumValueComments);
            Map<String, Object> array = entry("array");
            array.put("items", items);
            return array;
        }

        return convertSingleField(field, visited, depth, fieldComments, enumValueComments);
    }

    private Map<String, Object> convertSingleField(Descriptors.FieldDescriptor field, Set<String> visited, int depth,
                                                    Map<String, String> fieldComments,
                                                    Map<String, String> enumValueComments) {
        int maxDepth = properties.getSchemaMaxDepth();
        return switch (field.getJavaType()) {
            case INT, LONG -> entry("integer");
            case FLOAT, DOUBLE -> entry("number");
            case BOOLEAN -> entry("boolean");
            case STRING -> entry("string", "format", wellKnownTypes.getSchemaFormat(field));
            case BYTE_STRING -> entry("string", "contentEncoding", "base64");
            case ENUM -> enumEntry(field);
            case MESSAGE -> messageEntry(field, visited, depth, maxDepth, fieldComments, enumValueComments);
            default -> entry("string");
        };
    }

    private Map<String, Object> enumEntry(Descriptors.FieldDescriptor field) {
        Map<String, Object> s = entry("string");
        List<String> enumValues = field.getEnumType().getValues().stream()
                .map(Descriptors.EnumValueDescriptor::getName)
                .toList();
        if (!enumValues.isEmpty()) {
            s.put("enum", enumValues);
        }
        return s;
    }

    private Map<String, Object> messageEntry(Descriptors.FieldDescriptor field, Set<String> visited, int depth,
                                              int maxDepth,
                                              Map<String, String> fieldComments,
                                              Map<String, String> enumValueComments) {
        String msgName = field.getMessageType().getFullName();
        String wktFormat = wellKnownTypes.getSchemaFormat(msgName);
        if (wktFormat != null) {
            return entry("string", "format", wktFormat);
        }
        if (depth >= maxDepth || visited.contains(msgName)) {
            return entry("string", "description", "Message: " + msgName);
        }
        visited.add(msgName);
        return toJsonSchema(field.getMessageType(), visited, depth + 1, fieldComments, enumValueComments);
    }

    private void addFieldDescription(Map<String, Object> schema, Descriptors.FieldDescriptor field,
                                      Map<String, String> fieldComments,
                                      Map<String, String> enumValueComments) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM) {
            String enumDesc = buildEnumDescription(field, enumValueComments);
            if (enumDesc != null) {
                schema.put("description", enumDesc);
                return;
            }
        }

        StringJoiner desc = new StringJoiner(". ");
        String fieldComment = fieldComments != null ? fieldComments.get(field.getName()) : null;
        if (fieldComment != null && !fieldComment.isBlank()) {
            desc.add(fieldComment);
        }
        desc.add(isOptionalField(field) ? "optional" : "required");
        if (field.getContainingOneof() != null) {
            desc.add("oneof: " + field.getContainingOneof().getName());
        }
        if (field.isMapField()) {
            desc.add("proto type: " + buildMapProtoType(field));
        } else {
            desc.add("proto type: " + field.getType().name());
        }
        schema.put("description", desc.toString());
    }

    private String buildMapProtoType(Descriptors.FieldDescriptor field) {
        var keyField = field.getMessageType().findFieldByName("key");
        String keyType = keyField != null ? keyField.getType().name().toLowerCase() : "string";
        var valueField = field.getMessageType().findFieldByName("value");
        String valueType = valueField != null ? valueField.getType().name().toLowerCase() : "unknown";
        return "MAP<" + keyType + ", " + valueType + ">";
    }

    private String buildEnumDescription(Descriptors.FieldDescriptor field, Map<String, String> enumValueComments) {
        String enumTypeName = field.getEnumType().getFullName();
        List<String> parts = new ArrayList<>();
        boolean hasAnyComment = false;

        for (Descriptors.EnumValueDescriptor val : field.getEnumType().getValues()) {
            String valName = val.getName();
            String comment = enumValueComments != null ? enumValueComments.get(enumTypeName + "." + valName) : null;
            if (comment != null && !comment.isBlank()) {
                parts.add(valName + " — " + comment);
                hasAnyComment = true;
            } else {
                parts.add(valName);
            }
        }

        if (!hasAnyComment) {
            return null;
        }

        return "Доступные значения: " + String.join(", ", parts) + " (enum)";
    }
}
