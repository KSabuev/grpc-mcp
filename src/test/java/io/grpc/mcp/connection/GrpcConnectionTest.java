package io.grpc.mcp.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.config.GrpcProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrpcConnectionTest {

    @Mock HttpServletRequest request;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(request.getHeader(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(null);
    }

    @Test
    void resolve_allFromHeaders() {
        // Arrange
        var env = new GrpcProperties();
        env.setAddress("env-host:50051");
        env.setNegotiationType("TLS");
        env.setProtoFile("env.proto");
        env.setMetadataJson("{\"x-env\": \"1\"}");

        when(request.getHeader(GrpcConnection.HEADER_HOST)).thenReturn("client-host");
        when(request.getHeader(GrpcConnection.HEADER_PORT)).thenReturn("6000");
        when(request.getHeader(GrpcConnection.HEADER_NEGOTIATION)).thenReturn("TLS");
        when(request.getHeader(GrpcConnection.HEADER_PROTO_FILE)).thenReturn("client.proto");
        when(request.getHeader(GrpcConnection.HEADER_METADATA)).thenReturn("{\"x-client\": \"2\"}");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.target()).isEqualTo("client-host:6000");
        assertThat(connection.negotiation()).isEqualTo("TLS");
        assertThat(connection.protoRef()).isEqualTo("client.proto");
        assertThat(connection.metadata())
                .containsEntry("x-env", "1")
                .containsEntry("x-client", "2");
    }

    @Test
    void resolve_allFromEnv() {
        // Arrange
        var env = new GrpcProperties();
        env.setAddress("env-host:50051");
        env.setNegotiationType("TLS");
        env.setProtoFile("env.proto");
        env.setMetadataJson("{\"x-env\": \"1\"}");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.target()).isEqualTo("env-host:50051");
        assertThat(connection.negotiation()).isEqualTo("TLS");
        assertThat(connection.protoRef()).isEqualTo("env.proto");
        assertThat(connection.metadata()).containsEntry("x-env", "1");
        assertThat(connection.usesReflection()).isFalse();
    }

    @Test
    void resolve_defaults_whenNothingConfigured() {
        // Arrange

        // Act
        var connection = GrpcConnection.resolve(request, new GrpcProperties(), objectMapper);

        // Assert
        assertThat(connection.hasTarget()).isFalse();
        assertThat(connection.negotiation()).isEqualTo("PLAINTEXT");
        assertThat(connection.usesReflection()).isTrue();
        assertThat(connection.metadata()).isEmpty();
    }

    @Test
    void channelKey_includesTargetAndNegotiation() {
        // Arrange
        var connection = new GrpcConnection("host:50051", "plaintext", Map.of(), null);

        // Act

        // Assert
        assertThat(connection.channelKey()).isEqualTo("host:50051|PLAINTEXT|");
    }

    @Test
    void channelKey_differsByMetadata() {
        // Arrange
        var c1 = new GrpcConnection("host:50051", "PLAINTEXT", Map.of(), null);
        var c2 = new GrpcConnection("host:50051", "PLAINTEXT", Map.of("x-user", "a"), null);
        var c3 = new GrpcConnection("host:50051", "PLAINTEXT", Map.of("x-user", "b"), null);

        // Act

        // Assert
        assertThat(c1.channelKey()).isNotEqualTo(c2.channelKey());
        assertThat(c2.channelKey()).isNotEqualTo(c3.channelKey());
    }

    @Test
    void legacyUploadRef_notReflection() {
        // Arrange
        var connection = new GrpcConnection("host:50051", "PLAINTEXT", Map.of(), "upload/abc.proto");

        // Act

        // Assert
        assertThat(connection.usesReflection()).isFalse();
        assertThat(connection.isClientPath()).isFalse();
    }

    @Test
    void isClientPath_absoluteAndTildePaths() {
        // Arrange

        // Act

        // Assert
        assertThat(new GrpcConnection("h:1", "PLAINTEXT", Map.of(), "/home/user/svc.proto").isClientPath()).isTrue();
        assertThat(new GrpcConnection("h:1", "PLAINTEXT", Map.of(), "~/protos/svc.proto").isClientPath()).isTrue();
    }

    @Test
    void isClientPath_falseForServerRefs() {
        // Arrange

        // Act

        // Assert
        assertThat(new GrpcConnection("h:1", "PLAINTEXT", Map.of(), "svc.proto").isClientPath()).isFalse();
        assertThat(new GrpcConnection("h:1", "PLAINTEXT", Map.of(), "dir/svc.proto").isClientPath()).isFalse();
        assertThat(new GrpcConnection("h:1", "PLAINTEXT", Map.of(), null).isClientPath()).isFalse();
    }

    @Test
    void clientPathRequest_containsPathToolNameAndMissingFilePhrase() {
        // Arrange

        // Act
        var msg = GrpcConnection.clientPathRequest("/home/user/svc.proto");

        // Assert
        assertThat(msg)
                .contains("/home/user/svc.proto")
                .contains("load_proto_file")
                .contains("отсутствует по пути, указанному в конфиге: /home/user/svc.proto")
                .contains("памяти");
    }

    @Test
    void resolve_addressHeader_takesPrecedence() {
        // Arrange
        var env = new GrpcProperties();
        env.setAddress("env-host:50051");

        when(request.getHeader(GrpcConnection.HEADER_ADDRESS)).thenReturn(" addr-host:443 ");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.target()).isEqualTo("addr-host:443");
    }

    @Test
    void resolve_invalidPort_fallsBackToDefaultPort() {
        // Arrange
        var env = new GrpcProperties();

        when(request.getHeader(GrpcConnection.HEADER_HOST)).thenReturn("client-host");
        when(request.getHeader(GrpcConnection.HEADER_PORT)).thenReturn("not-a-number");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.target()).isEqualTo("client-host:" + GrpcConnection.DEFAULT_PORT);
    }

    @Test
    void resolve_noPortHeader_usesDefaultPort() {
        // Arrange
        var env = new GrpcProperties();

        when(request.getHeader(GrpcConnection.HEADER_HOST)).thenReturn("client-host");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.target()).isEqualTo("client-host:" + GrpcConnection.DEFAULT_PORT);
    }

    @Test
    void resolve_metadataWithNullValues_filtersThem() {
        // Arrange
        var env = new GrpcProperties();

        when(request.getHeader(GrpcConnection.HEADER_METADATA)).thenReturn("{\"a\": \"1\", \"b\": null}");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.metadata()).containsOnly(entry("a", "1"));
    }

    @Test
    void resolve_invalidMetadataJson_ignored() {
        // Arrange
        var env = new GrpcProperties();
        env.setMetadataJson("{\"env-key\": \"1\"}");

        when(request.getHeader(GrpcConnection.HEADER_METADATA)).thenReturn("{bad json");

        // Act
        var connection = GrpcConnection.resolve(request, env, objectMapper);

        // Assert
        assertThat(connection.metadata()).containsOnly(entry("env-key", "1"));
    }

    @Test
    void fixMojibake_restoresUtf8CyrillicFromLatin1DoubleEncoding() {
        // Arrange
        // wire: UTF-8 bytes of "Загрузки" decoded by the servlet as ISO-8859-1
        String mojibake = new String("/home/q1/Загрузки/svc.proto".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.ISO_8859_1);

        // Act

        // Assert
        assertThat(GrpcConnection.fixMojibake(mojibake)).isEqualTo("/home/q1/Загрузки/svc.proto");
    }

    @Test
    void fixMojibake_leavesAsciiAndCleanValuesUntouched() {
        // Arrange

        // Act

        // Assert
        assertThat(GrpcConnection.fixMojibake("/home/user/svc.proto")).isEqualTo("/home/user/svc.proto");
        assertThat(GrpcConnection.fixMojibake(null)).isNull();
        assertThat(GrpcConnection.fixMojibake("  ")).isEqualTo("  ");
        // a genuine latin-1 value (0xE9 is not a valid UTF-8 sequence) stays as-is
        assertThat(GrpcConnection.fixMojibake("caf\u00E9.proto")).isEqualTo("caf\u00E9.proto");
        // genuine Unicode (chars > 0xFF) is not a latin-1-decoded header — left untouched
        assertThat(GrpcConnection.fixMojibake("/home/q1/Загрузки/svc.proto"))
                .isEqualTo("/home/q1/Загрузки/svc.proto");
    }
}
