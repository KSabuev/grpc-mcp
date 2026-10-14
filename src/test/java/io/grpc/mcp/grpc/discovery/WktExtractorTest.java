package io.grpc.mcp.grpc.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WktExtractorTest {

    @TempDir Path wktDir;

    @Test
    void extractExistingWktFile_fileCreated() throws Exception {
        // Arrange

        // Act
        var extracted = new WktExtractor().extract(wktDir, "google/type/decimal.proto");

        // Assert
        assertThat(extracted).isTrue();
        assertThat(Files.exists(wktDir.resolve("google/type/decimal.proto"))).isTrue();
        assertThat(Files.readString(wktDir.resolve("google/type/decimal.proto"))).contains("message Decimal");
    }

    @Test
    void extractUnknownPath_returnsFalse() {
        // Arrange

        // Act
        var extracted = new WktExtractor().extract(wktDir, "google/type/nope.proto");

        // Assert
        assertThat(extracted).isFalse();
        assertThat(Files.exists(wktDir.resolve("google/type/nope.proto"))).isFalse();
    }

    @Test
    void extractDisallowedPath_rejected() {
        // Arrange

        // Act
        var extracted = new WktExtractor().extract(wktDir, "../etc/passwd");

        // Assert
        assertThat(extracted).isFalse();
    }

    @Test
    void extractTwice_idempotent() throws Exception {
        // Arrange
        var extractor = new WktExtractor();
        extractor.extract(wktDir, "google/api/http.proto");

        // Act
        var extracted = extractor.extract(wktDir, "google/api/http.proto");

        // Assert
        assertThat(extracted).isTrue();
        assertThat(Files.readString(wktDir.resolve("google/api/http.proto"))).contains("message Http");
    }
}
