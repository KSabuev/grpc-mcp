package io.grpc.mcp.mcp;

public final class JsonSchemaKeys {

    private JsonSchemaKeys() {}

    public static final String TYPE = "type";
    public static final String PROPERTIES = "properties";
    public static final String DESCRIPTION = "description";
    public static final String REQUIRED = "required";
    public static final String ITEMS = "items";
    public static final String ENUM = "enum";
    public static final String FORMAT = "format";
    public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    public static final String CONTENT_ENCODING = "contentEncoding";
    public static final String ONE_OF = "oneOf";

    public static final String CIRCULAR_REFERENCE_PREFIX = "Message: ";
}
