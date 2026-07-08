package io.grpc.mcp.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Metadata;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import io.grpc.mcp.grpc.channel.MetadataInterceptor;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import io.grpc.mcp.mcp.ChannelHealthMonitor;
import io.grpc.mcp.mcp.tool.McpTool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableConfigurationProperties({GrpcProperties.class, McpProperties.class})
@Slf4j
public class McpServerConfig {

    private McpSyncServer mcpServer;

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    @Bean
    public MetadataInterceptor grpcMetadataInterceptor(GrpcProperties properties, ObjectMapper objectMapper) {
        Metadata headers = new Metadata();
        String json = properties.getMetadataJson();
        if (json != null && !json.isBlank() && !"{}".equals(json.trim())) {
            try {
                Map<String, String> parsed = objectMapper.readValue(json,
                        new TypeReference<Map<String, String>>() {});
                parsed.forEach((k, v) -> {
                    Metadata.Key<String> key = Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER);
                    headers.put(key, v);
                });
                log.debug("Configured {} global metadata headers: {}", parsed.size(), parsed.keySet());
            } catch (Exception e) {
                log.warn("Failed to parse GRPC_METADATA JSON: {}", e.getMessage(), e);
            }
        }
        return new MetadataInterceptor(headers);
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/mcp");
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService taskExecutor() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "mcp-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public RetryTemplate grpcRetryTemplate(GrpcProperties properties,
                                            ChannelHealthMonitor channelHealthMonitor) {
        int maxAttempts = properties.getMaxRetries() + 1;

        GrpcRetryPolicy retryPolicy = new GrpcRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(properties.getBackoffInitial().toMillis());
        backOff.setMultiplier(properties.getRetryBackoffMultiplier());
        backOff.setMaxInterval(properties.getRetryMaxDelay().toMillis());

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOff);
        template.registerListener(new ChannelRecoveryRetryListener(channelHealthMonitor));

        log.info("gRPC retry configured: maxAttempts={} ({} retries), initialInterval={}ms, multiplier={}, maxDelay={}ms",
                maxAttempts, properties.getMaxRetries(),
                properties.getBackoffInitial().toMillis(),
                properties.getRetryBackoffMultiplier(),
                properties.getRetryMaxDelay().toMillis());
        return template;
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications(List<McpTool> tools) {
        return tools.stream().map(McpTool::build).toList();
    }

    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                    McpProperties mcpProperties,
                                    List<McpServerFeatures.SyncToolSpecification> tools,
                                    ServiceDiscoverer discovery) {
        log.info("Discovered {} gRPC methods, registered {} MCP tools",
                discovery.getMethods().size(), tools.size());

        this.mcpServer = McpServer.sync(transport)
                .serverInfo(mcpProperties.getName(), "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .strictToolNameValidation(false)
                .tools(tools)
                .build();

        return this.mcpServer;
    }

    @PreDestroy
    public void close() {
        log.info("Shutting down MCP server");
        if (mcpServer != null) {
            mcpServer.close();
        }
    }
}
