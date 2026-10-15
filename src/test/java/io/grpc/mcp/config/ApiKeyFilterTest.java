package io.grpc.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.io.PrintWriter;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyFilterTest {

    private ApiKeyFilter filter;

    @Mock HttpServletRequest req;
    @Mock HttpServletResponse res;
    @Mock FilterChain chain;
    @Mock PrintWriter writer;

    @BeforeEach
    void setUp() {
        var props = new McpProperties();
        props.setApiKey("my-secret-key");
        filter = new ApiKeyFilter(props);
    }

    private void mockWriter() {
        var mockRes = res;
        try {
            doReturn(writer).when(mockRes).getWriter();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void allowsActuatorWithoutApiKey() throws Exception {
        // Arrange
        when(req.getRequestURI()).thenReturn("/actuator/health");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(401);
    }

    @Test
    void blocksMcpPathWithoutApiKey() throws Exception {
        // Arrange
        mockWriter();
        when(req.getRequestURI()).thenReturn("/mcp/tools/call");
        when(req.getHeader("X-API-Key")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(res).setStatus(401);
        verify(res).getWriter();
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void blocksMcpPathWithWrongApiKey() throws Exception {
        // Arrange
        mockWriter();
        when(req.getRequestURI()).thenReturn("/mcp/tools/call");
        when(req.getHeader("X-API-Key")).thenReturn("wrong-key");
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(res).setStatus(401);
        verify(chain, never()).doFilter(req, res);
    }

    @Test
    void allowsMcpPathWithCorrectApiKey() throws Exception {
        // Arrange
        when(req.getRequestURI()).thenReturn("/mcp/tools/call");
        when(req.getHeader("X-API-Key")).thenReturn("my-secret-key");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(401);
    }

    @Test
    void allowsNonMcpPathWithoutApiKey() throws Exception {
        // Arrange
        when(req.getRequestURI()).thenReturn("/some-other-path");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(401);
    }

    @Test
    void allowsAllPathsWhenApiKeyEmpty() throws Exception {
        // Arrange
        var emptyProps = new McpProperties();
        emptyProps.setApiKey("");
        filter = new ApiKeyFilter(emptyProps);

        when(req.getRequestURI()).thenReturn("/mcp/tools/call");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(401);
    }

    @Test
    void allowsMcpPathWhenApiKeyNull() throws Exception {
        // Arrange
        var nullProps = new McpProperties();
        nullProps.setApiKey(null);
        filter = new ApiKeyFilter(nullProps);
        when(req.getRequestURI()).thenReturn("/mcp");

        // Act
        filter.doFilter(req, res, chain);

        // Assert
        verify(chain).doFilter(req, res);
        verify(res, never()).setStatus(401);
    }
}
