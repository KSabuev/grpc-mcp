package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ToolResults {

    public static McpSchema.CallToolResult success(String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .build();
    }

    public static McpSchema.CallToolResult error(String text) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .isError(true)
                .build();
    }
}
