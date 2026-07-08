package io.grpc.mcp.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

public class SchemaMap {
    public static Map<String, Object> entry(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        return m;
    }

    public static Map<String, Object> entry(String type, String k, Object v) {
        Map<String, Object> m = entry(type);
        if (v != null) m.put(k, v);
        return m;
    }
}
