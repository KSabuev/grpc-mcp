package io.grpc.mcp.mcp;

import com.google.protobuf.Descriptors;
import io.grpc.mcp.config.GrpcProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WellKnownTypes {

    private final GrpcProperties properties;

    private Map<String, GrpcProperties.WktInfo> wktMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        wktMap = new LinkedHashMap<>(properties.getWellKnownTypes());
    }

    public String getSchemaFormat(String typeName) {
        GrpcProperties.WktInfo info = wktMap.get(typeName);
        return info != null ? info.format() : null;
    }

    public String getSchemaFormat(Descriptors.FieldDescriptor field) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            return getSchemaFormat(field.getMessageType().getFullName());
        }
        return null;
    }

    public String getExample(String typeName) {
        GrpcProperties.WktInfo info = wktMap.get(typeName);
        return info != null ? info.example() : null;
    }

    public String getExample(Descriptors.FieldDescriptor field) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            return getExample(field.getMessageType().getFullName());
        }
        return null;
    }

}
