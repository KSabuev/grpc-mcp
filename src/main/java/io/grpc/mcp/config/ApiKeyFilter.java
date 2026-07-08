package io.grpc.mcp.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
@Slf4j
public class ApiKeyFilter implements Filter {

    private final String apiKey;

    public ApiKeyFilter(McpProperties mcpProperties) {
        this.apiKey = mcpProperties.getApiKey();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest req) ||
                !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }
        String path = req.getRequestURI();

        if (apiKey.isEmpty() || path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        if (!path.startsWith("/mcp")) {
            chain.doFilter(request, response);
            return;
        }

        String headerKey = req.getHeader("X-API-Key");

        if (!apiKey.equals(headerKey)) {
            log.warn("Missing or invalid X-API-Key from {}", req.getRemoteAddr());
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Unauthorized: missing or invalid X-API-Key\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
