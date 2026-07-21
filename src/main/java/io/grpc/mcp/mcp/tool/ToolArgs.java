package io.grpc.mcp.mcp.tool;

import io.grpc.mcp.mcp.JsonSchemaKeys;
import io.grpc.mcp.mcp.JsonSchemaType;

import java.util.Map;

public final class ToolArgs {

    private ToolArgs() {}

    public static final String METHOD = "method";
    public static final String ARGUMENTS = "arguments";
    public static final String METADATA = "metadata";
    public static final String MAX_SERVER_MESSAGES = "maxServerMessages";

    public static final String METHOD_DESCRIPTION = "Полное имя метода из list_methods";

    public static final Map<String, Object> EMPTY_INPUT_SCHEMA =
            Map.of(JsonSchemaKeys.TYPE, JsonSchemaType.OBJECT.getValue(), JsonSchemaKeys.PROPERTIES, Map.of());
}
