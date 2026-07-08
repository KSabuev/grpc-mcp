package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;

public interface McpTool {
    McpServerFeatures.SyncToolSpecification build();
}
