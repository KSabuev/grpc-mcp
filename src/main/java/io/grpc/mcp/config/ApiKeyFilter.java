package io.grpc.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
@Slf4j
public class ApiKeyFilter extends OncePerRequestFilter {

    private final String apiKey;

    public ApiKeyFilter(McpProperties mcpProperties) {
        String key = mcpProperties.getApiKey();
        this.apiKey = key == null ? "" : key;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        String path = req.getRequestURI();

        if (apiKey.isEmpty() || path.startsWith("/actuator") || !path.startsWith("/mcp")) {
            chain.doFilter(req, res);
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

        chain.doFilter(req, res);
    }
}
