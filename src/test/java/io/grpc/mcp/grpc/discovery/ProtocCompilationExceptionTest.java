package io.grpc.mcp.grpc.discovery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocCompilationExceptionTest {

    @Test
    void parsesFileLineColumn_fromProtocOutput() {
        // Arrange
        var e = new ProtocCompilationException(2, "svc.proto: 5:10: Expected identifier, found '}'\n");

        // Act & Assert
        assertThat(e.getFile()).isEqualTo("svc.proto");
        assertThat(e.getLine()).isEqualTo(5);
        assertThat(e.getColumn()).isEqualTo(10);
        assertThat(e.getFirstError()).isEqualTo("Expected identifier, found '}'");
        assertThat(e.getExitCode()).isEqualTo(2);
        assertThat(e.getRawOutput()).contains("Expected identifier");
        assertThat(e.getMessage()).contains("protoc failed with exit code 2");
    }

    @Test
    void parsesFirstLocation_only() {
        // Arrange
        var e = new ProtocCompilationException(2, """
                a.proto: 1:2: first error
                b.proto: 3:4: second error
                """);

        // Act & Assert
        assertThat(e.getFile()).isEqualTo("a.proto");
        assertThat(e.getLine()).isEqualTo(1);
        assertThat(e.getColumn()).isEqualTo(2);
    }

    @Test
    void noLocationInOutput_fieldsAreEmpty() {
        // Arrange
        var e = new ProtocCompilationException(1, "weird protoc output without location\n");

        // Act & Assert
        assertThat(e.getFile()).isNull();
        assertThat(e.getLine()).isZero();
        assertThat(e.getColumn()).isZero();
        assertThat(e.getFirstError()).isNull();
        assertThat(e.getRawOutput()).isNotBlank();
    }

    @Test
    void nullOutput_doesNotThrow() {
        // Arrange & Act
        var e = new ProtocCompilationException(3, null);

        // Assert
        assertThat(e.getFile()).isNull();
        assertThat(e.getRawOutput()).isEmpty();
        assertThat(e.getMessage()).contains("protoc failed with exit code 3");
    }
}
