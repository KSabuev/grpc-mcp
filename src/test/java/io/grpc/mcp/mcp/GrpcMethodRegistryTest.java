package io.grpc.mcp.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.grpc.mcp.exceptions.MethodNotFoundException;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrpcMethodRegistryTest {

    @Mock ServiceDiscoverer discovery;

    private final MethodMeta m1 = MethodMeta.builder()
            .serviceName("test.Svc").methodName("DoIt")
            .fullMethodName("test.Svc/DoIt")
            .serverStreaming(false).clientStreaming(false)
            .build();
    private final MethodMeta m2 = MethodMeta.builder()
            .serviceName("test.Svc").methodName("Get")
            .fullMethodName("test.Svc/Get")
            .serverStreaming(true).clientStreaming(false)
            .build();

    @Test
    void initPopulatesRegistry() {
        when(discovery.getMethods()).thenReturn(List.of(m1, m2));
        var registry = new GrpcMethodRegistry(discovery);
        registry.init();

        assertThat(registry.getAllMethodNames()).containsExactly("test.Svc/DoIt", "test.Svc/Get");
        assertThat(registry.getMethod("test.Svc/DoIt")).isSameAs(m1);
    }

    @Test
    void updateMethodsReplacesEntries() {
        var registry = new GrpcMethodRegistry(discovery);
        registry.updateMethods(List.of(m1, m2));

        assertThat(registry.getAllMethodNames()).hasSize(2);

        var m3 = MethodMeta.builder()
                .serviceName("other.Svc").methodName("Run")
                .fullMethodName("other.Svc/Run")
                .serverStreaming(false).clientStreaming(false)
                .build();
        registry.updateMethods(List.of(m3));

        assertThat(registry.getAllMethodNames()).containsExactly("other.Svc/Run");
    }

    @Test
    void updateMethodsWithEmptyListPreservesExisting() {
        when(discovery.getMethods()).thenReturn(List.of(m1));
        var registry = new GrpcMethodRegistry(discovery);
        registry.init();

        registry.updateMethods(List.of());

        assertThat(registry.getAllMethodNames()).containsExactly("test.Svc/DoIt");
    }

    @Test
    void updateMethodsWithNullPreservesExisting() {
        when(discovery.getMethods()).thenReturn(List.of(m1));
        var registry = new GrpcMethodRegistry(discovery);
        registry.init();

        registry.updateMethods(null);

        assertThat(registry.getAllMethodNames()).containsExactly("test.Svc/DoIt");
    }

    @Test
    void updateMethodsWithDuplicateKeysThrows() {
        var registry = new GrpcMethodRegistry(discovery);

        var dup = MethodMeta.builder()
                .serviceName("test.Svc").methodName("DoIt")
                .fullMethodName("test.Svc/DoIt")
                .build();

        assertThatThrownBy(() -> registry.updateMethods(List.of(m1, dup)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getMethodThrowsOnNotFound() {
        var registry = new GrpcMethodRegistry(discovery);
        registry.updateMethods(List.of(m1));

        assertThatThrownBy(() -> registry.getMethod("unknown"))
                .isInstanceOf(MethodNotFoundException.class)
                .hasMessageContaining("не найден");
    }
}
