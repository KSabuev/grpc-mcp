package io.grpc.mcp.mcp;

import io.grpc.mcp.exceptions.MethodNotFoundException;
import io.grpc.mcp.grpc.discovery.MethodMeta;
import io.grpc.mcp.grpc.discovery.ServiceDiscoverer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcMethodRegistry {

    private final ServiceDiscoverer discovery;

    private volatile Map<String, MethodMeta> methods = Map.of();
    private volatile List<String> methodNames = List.of();

    @PostConstruct
    public void init() {
        updateMethods(discovery.getMethods());
    }

    public synchronized void updateMethods(List<MethodMeta> methodList) {
        if (methodList == null || methodList.isEmpty()) {
            log.warn("Skipping registry update: empty method list (preserving existing {})", methods.size());
            return;
        }
        this.methods = methodList.stream()
                .collect(Collectors.toConcurrentMap(MethodMeta::fullMethodName, Function.identity()));
        this.methodNames = List.copyOf(
                methodList.stream().map(MethodMeta::fullMethodName).toList());
        log.info("Registry updated: {} methods", methodNames.size());
    }

    public List<String> getAllMethodNames() {
        return methodNames;
    }

    public MethodMeta getMethod(String fullName) throws MethodNotFoundException {
        MethodMeta meta = methods.get(fullName);
        if (meta == null) {
            throw new MethodNotFoundException("""
                    Метод "%s" не найден.
                    Используйте list_methods, чтобы получить точное полное имя.
                    Пример формата: "com.example.MyService/myMethod".""".formatted(fullName));
        }
        return meta;
    }
}
