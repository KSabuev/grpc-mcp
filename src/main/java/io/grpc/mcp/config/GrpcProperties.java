package io.grpc.mcp.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Data
@Validated
@ConfigurationProperties(prefix = "grpc")
public class GrpcProperties {

    @NotBlank
    private String address;

    private String negotiationType;

    private String protoFile;

    private String metadataJson;

    @NotNull
    private Duration callTimeout;

    @NotNull
    private Duration backoffInitial;

    @NotNull
    private Duration healthCheckInterval;

    @Positive
    private int maxRetries;

    @NotNull
    private Duration keepaliveTime;

    @NotNull
    private Duration keepaliveTimeout;

    private boolean keepaliveWithoutCalls;

    @Positive
    private double retryBackoffMultiplier;

    @NotNull
    private Duration retryMaxDelay;

    @NotNull
    private Duration protocTimeout;

    @Positive
    private int schemaMaxDepth;

    @NotNull
    private Duration reflectionTimeout;

    @NotNull
    private Duration channelShutdownTimeout;

    private boolean tlsInsecure;

    private String tlsTrustCert;

    private Map<String, WktInfo> wellKnownTypes = defaultWellKnownTypes();

    public record WktInfo(String format, String example, Object defaultValue) {}

    private static Map<String, WktInfo> defaultWellKnownTypes() {
        Map<String, WktInfo> defaults = new HashMap<>();
        defaults.put("google.protobuf.Timestamp", new WktInfo("date-time", "2024-01-01T00:00:00Z", null));
        defaults.put("google.type.Date", new WktInfo("date", "2024-01-01", null));
        defaults.put("google.protobuf.Duration", new WktInfo("duration", "1s", null));
        defaults.put("google.protobuf.Any", new WktInfo(null, "{}", null));
        defaults.put("google.protobuf.Struct", new WktInfo(null, "{}", null));
        defaults.put("google.protobuf.Value", new WktInfo(null, "null", null));
        defaults.put("google.protobuf.ListValue", new WktInfo(null, "[]", null));
        defaults.put("google.protobuf.Empty", new WktInfo(null, "{}", null));
        defaults.put("google.protobuf.FieldMask", new WktInfo(null, "{\"paths\": []}", null));
        defaults.put("google.protobuf.BoolValue", new WktInfo(null, "false", null));
        defaults.put("google.protobuf.Int32Value", new WktInfo(null, "0", null));
        defaults.put("google.protobuf.Int64Value", new WktInfo(null, "0", null));
        defaults.put("google.protobuf.UInt32Value", new WktInfo(null, "0", null));
        defaults.put("google.protobuf.UInt64Value", new WktInfo(null, "0", null));
        defaults.put("google.protobuf.FloatValue", new WktInfo(null, "0.0", null));
        defaults.put("google.protobuf.DoubleValue", new WktInfo(null, "0.0", null));
        defaults.put("google.protobuf.StringValue", new WktInfo(null, "\"\"", null));
        defaults.put("google.protobuf.BytesValue", new WktInfo(null, "\"\"", null));
        return defaults;
    }
}
