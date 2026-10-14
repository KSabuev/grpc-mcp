package io.grpc.mcp.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Validated
@ConfigurationProperties(prefix = "grpc")
public class GrpcProperties {

    private String address = "";

    private String negotiationType;

    private String protoFile;

    private String metadataJson;

    @NotNull
    private Duration callTimeout;

    @NotNull
    private Duration backoffInitial;

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

    private String protoBaseDir = "./protos";

    private String wktDir = "./wkt";

    private Duration connectionIdleTimeout = Duration.ofMinutes(30);

    private Duration sessionProtoIdleTimeout = Duration.ofHours(1);

    @Positive
    private int maxUploadSize = 1048576;
}
