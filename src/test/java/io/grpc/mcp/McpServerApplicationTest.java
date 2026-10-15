package io.grpc.mcp;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.TaskScheduler;
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

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    TaskScheduler taskScheduler;

    @Test
    void protoCache_caffeineMetricsRegistered() {
        assertThat(hasMeter("cache.size", "cache", "protoCache")).isTrue();
        assertThat(hasMeter("cache.gets", "cache", "protoCache")).isTrue();
        assertThat(hasMeter("cache.puts", "cache", "protoCache")).isTrue();
        assertThat(hasMeter("cache.evictions", "cache", "protoCache")).isTrue();
    }

    private boolean hasMeter(String name, String tagKey, String tagValue) {
        return meterRegistry.getMeters().stream()
                .anyMatch(m -> m.getId().getName().equals(name)
                        && m.getId().getTags().stream()
                        .anyMatch(t -> t.getKey().equals(tagKey) && t.getValue().equals(tagValue)));
    }

    @Test
    void enableScheduling_providesTaskScheduler() {
        assertThat(taskScheduler).isNotNull();
    }

    @Test
    void contextLoads_createsEmptyWktDir() throws Exception {
        // Arrange

        // Act

        // Assert
        assertThat(connectionProvider).isNotNull();
        assertThat(Files.isDirectory(connectionProvider.wktDirPath())).isTrue();
        assertThat(Files.list(connectionProvider.wktDirPath()).count()).isZero();
    }
}
