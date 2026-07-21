package io.grpc.mcp.mcp.tool;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultsTest {

    private static String text(McpSchema.CallToolResult r) {
        return ((McpSchema.TextContent) r.content().get(0)).text();
    }

    @Test
    void success_returnsTextWithNoError() {
        var result = ToolResults.success("some data");
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("some data");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void error_returnsTextWithIsError() {
        var result = ToolResults.error("something went wrong");
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("something went wrong");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void success_emptyString() {
        var result = ToolResults.success("");
        assertThat(text(result)).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void error_emptyString() {
        var result = ToolResults.error("");
        assertThat(text(result)).isEmpty();
        assertThat(result.isError()).isTrue();
    }
}
