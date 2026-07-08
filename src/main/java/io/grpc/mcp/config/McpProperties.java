package io.grpc.mcp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    @NotBlank
    private String apiKey;

    @NotBlank
    private String name = "grpc-mcp";
}
