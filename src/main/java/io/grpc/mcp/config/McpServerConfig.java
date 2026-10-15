package io.grpc.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.grpc.mcp.connection.GrpcConnection;
import io.grpc.mcp.mcp.tool.McpTool;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.Map;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({GrpcProperties.class, McpProperties.class})
@Slf4j
public class McpServerConfig {

    @Bean(destroyMethod = "")
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(
            GrpcProperties properties, ObjectMapper objectMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .contextExtractor(connectionContextExtractor(properties, objectMapper))
                .build();
    }

    static McpTransportContextExtractor<HttpServletRequest> connectionContextExtractor(
            GrpcProperties env, ObjectMapper objectMapper) {
        return request -> {
            GrpcConnection connection = GrpcConnection.resolve(request, env, objectMapper);
            return McpTransportContext.create(Map.of(GrpcConnection.CONTEXT_KEY, connection));
        };
    }

    @Bean
    public McpJsonMapper mcpJsonMapper() {
        return McpJsonDefaults.getMapper();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transport) {
        return new ServletRegistrationBean<>(transport, "/mcp");
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> toolSpecifications(List<McpTool> tools) {
        return tools.stream().map(McpTool::build).toList();
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                    McpProperties mcpProperties,
                                    List<McpServerFeatures.SyncToolSpecification> tools) {
        log.info("Registered {} MCP tools (per-client connections via GRPC-* headers)", tools.size());
        return McpServer.sync(transport)
                .serverInfo(mcpProperties.getName(), "1.6.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .strictToolNameValidation(false)
                .tools(tools)
                .build();
    }
}
