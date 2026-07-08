<div align="center">

# grpc-mcp — Universal MCP Bridge for Any gRPC Service

<p>
  <img src="https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?style=for-the-badge&logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Gradle-8.x-02303A?style=for-the-badge&logo=gradle&logoColor=white">
  <img src="https://img.shields.io/badge/gRPC-1.76-2441E0?style=for-the-badge&logo=grpc&logoColor=white">
</p>

**Bridge any gRPC service to the MCP ecosystem** — auto-discovers methods via reflection or `.proto` files, generates JSON Schema for arguments, and exposes them as MCP tools with health monitoring, retry, and TLS support.

</div>

---

## Features

- **4 MCP tools** — `list_methods`, `get_method_schema`, `invoke_method`, `health`
- **2 discovery modes** — gRPC reflection or protoc-compiled `.proto` files
- **Resilience** — channel health monitoring, auto-reconnect, exponential-backoff retry
- **Security** — X-Api-Key authentication, TLS gRPC, custom metadata headers
- **Config-driven** — all settings via environment variables, single universal Docker image

## Quick Start

```bash
docker run -p 8080:8080 \
  -e GRPC_ADDRESS=your-grpc-host:port \
  -e MCP_NAME="My Service" \
  -e MCP_API_KEY=your-api-key \
  grpc-mcp:1.0
```

Then connect any MCP client:

```json
{
  "mcpServers": {
    "my-service": {
      "type": "remote",
      "url": "http://localhost:8080/mcp",
      "headers": {
        "X-API-Key": "<your-api-key>"
      }
    }
  }
}
```

## MCP Tools

| Tool | Description |
|---|---|
| `list_methods` | Lists all discovered gRPC methods as `ServiceName/methodName` |
| `get_method_schema` | JSON Schema of input arguments + example. Param: `method` |
| `invoke_method` | Call a gRPC method. Params: `method`, `arguments` (JSON) |
| `health` | gRPC channel state: `READY` / `IDLE` / `CONNECTING` / `TRANSIENT_FAILURE` |

## Discovery Modes

Auto-selected based on `GRPC_PROTO_FILE`:

- **FILE** — `.proto` file specified → compiled via `protoc` at startup, extracts schemas and source code comments
- **REFLECTION** — no proto file → uses gRPC `ServerReflectionService` (the server must support it)

## Environment Variables

### MCP

| Variable | Description | Default |
|---|---|---|
| `MCP_NAME` | MCP server display name | — |
| `MCP_API_KEY` | Auth key for `X-Api-Key` header | — |

### gRPC Connection

| Variable | Description | Default |
|---|---|---|
| `GRPC_ADDRESS` | gRPC target `host:port` | — |
| `GRPC_NEGOTIATION_TYPE` | `PLAINTEXT` or `TLS` | `PLAINTEXT` |
| `GRPC_PROTO_FILE` | Path to `.proto` file (empty = reflection) | — |
| `GRPC_METADATA` | JSON with extra gRPC headers | `{}` |

### Timeouts & Retry

| Variable | Description | Default |
|---|---|---|
| `GRPC_CALL_TIMEOUT` | Per-call timeout | `10s` |
| `GRPC_MAX_RETRIES` | Max retries (total attempts = value + 1) | `2` |
| `GRPC_BACKOFF_INITIAL` | Initial retry delay | `500ms` |
| `GRPC_RETRY_BACKOFF_MULTIPLIER` | Backoff multiplier | `2.0` |
| `GRPC_RETRY_MAX_DELAY` | Max retry delay | `3s` |

### Keepalive

| Variable | Description | Default |
|---|---|---|
| `GRPC_KEEPALIVE_TIME` | Keepalive ping interval | `10s` |
| `GRPC_KEEPALIVE_TIMEOUT` | Keepalive ping timeout | `5s` |
| `GRPC_KEEPALIVE_WITHOUT_CALLS` | Ping even without active calls | `true` |

### Discovery

| Variable | Description | Default |
|---|---|---|
| `GRPC_REFLECTION_TIMEOUT` | Reflection discovery/rediscovery timeout | `5s` |
| `GRPC_PROTOC_TIMEOUT` | Protoc compilation timeout | `10s` |
| `GRPC_SCHEMA_MAX_DEPTH` | Max depth for JSON Schema generation | `8` |

### Health Check

| Variable | Description | Default |
|---|---|---|
| `GRPC_HEALTH_CHECK_INTERVAL` | gRPC channel health check interval | `30s` |

## Architecture

```
┌──────────────┐         MCP         ┌────────────────────┐     gRPC     ┌──────────────────┐
│              │ ◄────────────────── │                    │ ◄─────────── │                  │
│  MCP Client  │                     │     grpc-mcp       │              │  gRPC Service    │
│  (opencode,  │ ──────────────────► │                    │ ───────────► │  (your app)      │
│  Claude, …)  │                     ├────────────────────┤              │                  │
└──────────────┘                     │  discovery layer   │              └──────────────────┘
                                     │  ├─ reflection     │
                                     │  └─ protoc .proto  │
                                     │  health monitor    │
                                     │  retry policy      │
                                     │  metadata inject   │
                                     └────────────────────┘
```

## Build & Test

```bash
# Build
./gradlew build

# Test 
./gradlew test
```

## Docker

```bash
# Build image
docker build -t grpc-mcp .

# Run
docker run -p 8080:8080 \
  -e GRPC_ADDRESS=your-grpc-host:port \
  -e MCP_NAME="My Service" \
  -e MCP_API_KEY=your-api-key \
  grpc-mcp
```

## Security

- All requests require `X-Api-Key` header (configured via `MCP_API_KEY`)
- Requests without a valid key are rejected with `401 Unauthorized`
- gRPC connection supports TLS via `GRPC_NEGOTIATION_TYPE=TLS`
- Custom gRPC metadata headers configured via `GRPC_METADATA` JSON

---

<div align="center">
  <sub>Built with Spring Boot, gRPC-Java, and the Model Context Protocol SDK</sub>
</div>
