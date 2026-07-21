package io.grpc.mcp.mcp;

import com.google.protobuf.Descriptors;
import io.grpc.mcp.config.GrpcProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static io.grpc.mcp.mcp.JsonSchemaKeys.CIRCULAR_REFERENCE_PREFIX;
import static io.grpc.mcp.mcp.JsonSchemaKeys.DESCRIPTION;

@Component
@RequiredArgsConstructor
public class ProtoSchemaConverter {

    private final GrpcProperties properties;
    private final WellKnownTypes wellKnownTypes;

    private int maxDepth;

    @PostConstruct
    public void init() {
        this.maxDepth = properties.getSchemaMaxDepth();
    }

    public Map<String, Object> toJsonSchema(
            Descriptors.Descriptor descriptor,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        return toJsonSchema(descriptor, new HashSet<>(), 0, fieldComments, enumValueComments);
    }

    private static boolean isOptionalField(Descriptors.FieldDescriptor field) {
        return field.isRepeated() || field.isMapField() || field.getContainingOneof() != null;
    }

    private Map<String, Object> toJsonSchema(
            Descriptors.Descriptor descriptor,
            Set<String> visited, int depth,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
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

        return JsonSchemaBuilder.object()
                .properties(properties)
                .required(required)
                .build();
    }

    private Map<String, Object> convertField(
            Descriptors.FieldDescriptor field,
            Set<String> visited, int depth,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        if (field.isMapField()) {
            return JsonSchemaBuilder.object()
                    .additionalProperties(convertSingleField(field, visited, depth, fieldComments, enumValueComments))
                    .build();
        }

        if (field.isRepeated()) {
            return JsonSchemaBuilder.array()
                    .items(convertSingleFieldBuilder(field, visited, depth, fieldComments, enumValueComments))
                    .build();
        }

        return convertSingleField(field, visited, depth, fieldComments, enumValueComments);
    }

    private Map<String, Object> convertSingleField(
            Descriptors.FieldDescriptor field,
            Set<String> visited, int depth,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        return convertSingleFieldBuilder(field, visited, depth, fieldComments, enumValueComments).build();
    }

    private JsonSchemaBuilder convertSingleFieldBuilder(
            Descriptors.FieldDescriptor field,
            Set<String> visited, int depth,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        return switch (field.getJavaType()) {
            case INT, LONG -> JsonSchemaBuilder.integer();
            case FLOAT, DOUBLE -> JsonSchemaBuilder.number();
            case BOOLEAN -> JsonSchemaBuilder.bool();
            case STRING -> JsonSchemaBuilder.string().format(wellKnownTypes.getSchemaFormat(field));
            case BYTE_STRING -> JsonSchemaBuilder.string().contentEncoding("base64");
            case ENUM -> enumBuilder(field);
            case MESSAGE -> messageBuilder(field, visited, depth, fieldComments, enumValueComments);
            default -> JsonSchemaBuilder.string();
        };
    }

    private JsonSchemaBuilder enumBuilder(Descriptors.FieldDescriptor field) {
        List<String> enumValues = field.getEnumType().getValues().stream()
                .map(Descriptors.EnumValueDescriptor::getName)
                .toList();
        return JsonSchemaBuilder.string().enumValues(enumValues);
    }

    private JsonSchemaBuilder messageBuilder(
            Descriptors.FieldDescriptor field,
            Set<String> visited, int depth,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        String msgName = field.getMessageType().getFullName();
        String wktFormat = wellKnownTypes.getSchemaFormat(msgName);
        if (wktFormat != null) {
            return JsonSchemaBuilder.string().format(wktFormat);
        }
        if (depth >= maxDepth || visited.contains(msgName)) {
            return JsonSchemaBuilder.string().description(CIRCULAR_REFERENCE_PREFIX + msgName);
        }
        visited.add(msgName);
        Map<String, Object> nestedSchema = toJsonSchema(field.getMessageType(), visited, depth + 1, fieldComments, enumValueComments);
        return JsonSchemaBuilder.object().putAll(nestedSchema);
    }

    private void addFieldDescription(
            Map<String, Object> schema,
            Descriptors.FieldDescriptor field,
            Map<String, String> fieldComments,
            Map<String, String> enumValueComments
    ) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM) {
            String enumDesc = buildEnumDescription(field, enumValueComments);
            if (enumDesc != null) {
                schema.put(DESCRIPTION, enumDesc);
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
        schema.put(DESCRIPTION, desc.toString());
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
