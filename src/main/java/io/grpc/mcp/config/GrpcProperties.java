package io.grpc.mcp.config;

import jakarta.validation.constraints.NotBlank;
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
}
