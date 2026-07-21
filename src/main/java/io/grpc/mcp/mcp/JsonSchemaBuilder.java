package io.grpc.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static io.grpc.mcp.mcp.JsonSchemaKeys.*;

@Slf4j
public final class JsonSchemaBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Object> schema = new LinkedHashMap<>();

    private JsonSchemaBuilder(String type) {
        schema.put(TYPE, type);
    }

    public static JsonSchemaBuilder object() {
        return new JsonSchemaBuilder(JsonSchemaType.OBJECT.getValue());
    }

    public static JsonSchemaBuilder array() {
        return new JsonSchemaBuilder(JsonSchemaType.ARRAY.getValue());
    }

    public static JsonSchemaBuilder string() {
        return new JsonSchemaBuilder(JsonSchemaType.STRING.getValue());
    }

    public static JsonSchemaBuilder integer() {
        return new JsonSchemaBuilder(JsonSchemaType.INTEGER.getValue());
    }

    public static JsonSchemaBuilder number() {
        return new JsonSchemaBuilder(JsonSchemaType.NUMBER.getValue());
    }

    public static JsonSchemaBuilder bool() {
        return new JsonSchemaBuilder(JsonSchemaType.BOOLEAN.getValue());
    }

    public JsonSchemaBuilder format(String format) {
        if (format != null) {
            schema.put(FORMAT, format);
        }
        return this;
    }

    public JsonSchemaBuilder description(String description) {
        if (description != null && !description.isBlank()) {
            schema.put(DESCRIPTION, description);
        }
        return this;
    }

    public JsonSchemaBuilder enumValues(List<String> values) {
        if (values != null && !values.isEmpty()) {
            schema.put(ENUM, values);
        }
        return this;
    }

    public JsonSchemaBuilder properties(Map<String, Object> properties) {
        if (properties != null) {
            schema.put(PROPERTIES, properties);
        }
        return this;
    }

    public JsonSchemaBuilder required(List<String> required) {
        if (required != null && !required.isEmpty()) {
            schema.put(REQUIRED, required);
        }
        return this;
    }

    public JsonSchemaBuilder additionalProperties(JsonSchemaBuilder additionalProperties) {
        if (additionalProperties != null) {
            schema.put(ADDITIONAL_PROPERTIES, additionalProperties.build());
        }
        return this;
    }

    public JsonSchemaBuilder additionalProperties(Map<String, Object> additionalProperties) {
        if (additionalProperties != null && !additionalProperties.isEmpty()) {
            schema.put(ADDITIONAL_PROPERTIES, additionalProperties);
        }
        return this;
    }

    public JsonSchemaBuilder additionalProperties(boolean allowed) {
        schema.put(ADDITIONAL_PROPERTIES, allowed);
        return this;
    }

    public JsonSchemaBuilder items(JsonSchemaBuilder items) {
        if (items != null) {
            schema.put(ITEMS, items.build());
        }
        return this;
    }

    public JsonSchemaBuilder contentEncoding(String encoding) {
        if (encoding != null) {
            schema.put(CONTENT_ENCODING, encoding);
        }
        return this;
    }

    public JsonSchemaBuilder put(String key, Object value) {
        if (value != null) {
            schema.put(key, value);
        }
        return this;
    }

    public JsonSchemaBuilder putAll(Map<String, Object> other) {
        if (other != null) {
            schema.putAll(other);
        }
        return this;
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(schema);
    }

    public static boolean isValidSchema(Map<String, Object> schemaMap) {
        try {
            String json = MAPPER.writeValueAsString(schemaMap);
            var factory = JsonSchemaFactory.byDefault();
            var schemaNode = MAPPER.readTree(json);
            factory.getJsonSchema(schemaNode);
            return true;
        } catch (Exception e) {
            log.debug("JSON Schema validation failed: {}", e.getMessage());
            return false;
        }
    }
}
