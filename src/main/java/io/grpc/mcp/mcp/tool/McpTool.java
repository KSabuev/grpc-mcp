package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.grpc.mcp.connection.GrpcConnection;

public interface McpTool {
    McpServerFeatures.SyncToolSpecification build();

    /**
     * Per-client connection resolved from the request headers by the transport
     * context extractor (headers -&gt; env -&gt; default).
     */
    static GrpcConnection connection(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext().get(GrpcConnection.CONTEXT_KEY);
        return value instanceof GrpcConnection connection ? connection : null;
    }

    /**
     * Current MCP session id (key for in-memory session-scoped state).
     */
    static String sessionId(McpSyncServerExchange exchange) {
        String sid = exchange.sessionId();
        return (sid != null && !sid.isBlank()) ? sid : "default";
    }
}
