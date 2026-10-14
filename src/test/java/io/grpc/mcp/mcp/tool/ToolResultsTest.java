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
        // Arrange

        // Act
        var result = ToolResults.success("some data");

        // Assert
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("some data");
        assertThat(result.isError()).isFalse();
    }

    @Test
    void error_returnsTextWithIsError() {
        // Arrange

        // Act
        var result = ToolResults.error("something went wrong");

        // Assert
        assertThat(result.content()).hasSize(1);
        assertThat(text(result)).isEqualTo("something went wrong");
        assertThat(result.isError()).isTrue();
    }

    @Test
    void success_emptyString() {
        // Arrange

        // Act
        var result = ToolResults.success("");

        // Assert
        assertThat(text(result)).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void error_emptyString() {
        // Arrange

        // Act
        var result = ToolResults.error("");

        // Assert
        assertThat(text(result)).isEmpty();
        assertThat(result.isError()).isTrue();
    }
}
