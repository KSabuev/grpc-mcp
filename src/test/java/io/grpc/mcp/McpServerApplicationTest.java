package io.grpc.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.grpc.mcp.connection.ConnectionProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "mcp.api-key=test-key",
        "mcp.name=grpc-mcp-test",
        "grpc.address=",
        "server.port=0"
})
class McpServerApplicationTest {

    @DynamicPropertySource
    static void tempDirs(DynamicPropertyRegistry registry) {
        try {
            Path base = Files.createTempDirectory("grpc-mcp-ctx-test");
            registry.add("grpc.proto-base-dir", () -> base.resolve("protos").toString());
            registry.add("grpc.wkt-dir", () -> base.resolve("wkt").toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    ConnectionProvider connectionProvider;

    @Test
    void contextLoads_createsEmptyWktDir() throws Exception {
        // Arrange
        // Spring context bootstrapped by @SpringBootTest, wkt dir configured to a temp path

        // Act
        // context load runs ConnectionProvider.init(), which must create the (empty) WKT dir

        // Assert
        assertThat(connectionProvider).isNotNull();
        assertThat(Files.isDirectory(connectionProvider.wktDirPath())).isTrue();
        assertThat(Files.list(connectionProvider.wktDirPath()).count()).isZero();
    }
}
