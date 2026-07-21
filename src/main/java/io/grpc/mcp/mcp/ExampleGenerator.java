package io.grpc.mcp.mcp;

import com.google.protobuf.Descriptors;
import io.grpc.mcp.config.GrpcProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static io.grpc.mcp.mcp.JsonSchemaKeys.CIRCULAR_REFERENCE_PREFIX;

@Component
@RequiredArgsConstructor
public class ExampleGenerator {

    private final GrpcProperties properties;
    private final WellKnownTypes wellKnownTypes;

    private int maxDepth;

    @PostConstruct
    public void init() {
        this.maxDepth = properties.getSchemaMaxDepth();
    }

    public Map<String, Object> generateExample(Descriptors.Descriptor descriptor) {
        return generateExample(descriptor, new HashSet<>(), 0);
    }

    private Map<String, Object> generateExample(Descriptors.Descriptor descriptor,
                                                  Set<String> visited, int depth) {
        Map<String, Object> example = new LinkedHashMap<>();

        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            example.put(field.getName(), generateFieldValue(field, visited, depth));
        }

        return example;
    }

    private Object generateFieldValue(Descriptors.FieldDescriptor field,
                                       Set<String> visited, int depth) {
        if (field.isMapField()) {
            return new LinkedHashMap<>();
        }
        if (field.isRepeated()) {
            return new ArrayList<>();
        }
        return generateSingleValue(field, visited, depth);
    }

    private Object generateSingleValue(Descriptors.FieldDescriptor field,
                                        Set<String> visited, int depth) {
        return switch (field.getJavaType()) {
            case INT, LONG -> 0;
            case FLOAT, DOUBLE -> 0.0;
            case BOOLEAN -> false;
            case STRING -> {
                String wkt = wellKnownTypes.getExample(field);
                yield wkt != null ? wkt : field.getName();
            }
            case BYTE_STRING -> "";
            case ENUM -> {
                var values = field.getEnumType().getValues();
                yield values.isEmpty() ? "ENUM_VALUE" : values.getFirst().getName();
            }
            case MESSAGE -> generateMessageExample(field.getMessageType(), visited, depth);
        };
    }

    private Object generateMessageExample(Descriptors.Descriptor msgType,
                                           Set<String> visited, int depth) {
        String name = msgType.getFullName();
        if (depth >= maxDepth || visited.contains(name)) {
            return CIRCULAR_REFERENCE_PREFIX + name;
        }

        String wkt = wellKnownTypes.getExample(name);
        if (wkt != null) {
            return wkt;
        }

        visited.add(name);
        return generateExample(msgType, visited, depth + 1);
    }
}
