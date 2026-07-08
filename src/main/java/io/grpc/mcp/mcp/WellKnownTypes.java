package io.grpc.mcp.mcp;

import com.google.protobuf.Descriptors;
import org.springframework.stereotype.Component;

@Component
public class WellKnownTypes {

    public String getSchemaFormat(String typeName) {
        return switch (typeName) {
            case "google.protobuf.Timestamp" -> "date-time";
            case "google.type.Date" -> "date";
            case "google.protobuf.Duration" -> "duration";
            default -> null;
        };
    }

    public String getSchemaFormat(Descriptors.FieldDescriptor field) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            return getSchemaFormat(field.getMessageType().getFullName());
        }
        return null;
    }

    public String getExample(String typeName) {
        return switch (typeName) {
            case "google.protobuf.Timestamp" -> "2024-01-01T00:00:00Z";
            case "google.type.Date" -> "2024-01-01";
            case "google.protobuf.Duration" -> "1s";
            default -> null;
        };
    }

    public String getExample(Descriptors.FieldDescriptor field) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            return getExample(field.getMessageType().getFullName());
        }
        return null;
    }
}
