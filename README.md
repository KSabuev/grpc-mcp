<div align="center">

# grpc-mcp — Universal MCP Bridge for Any gRPC Service

<p>
  <img src="https://img.shields.io/badge/version-1.6.0-4E79A7?style=for-the-badge">
  <img src="https://img.shields.io/badge/license-MIT-green?style=for-the-badge">
  <img src="https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white">
  <img src="https://img.shields.io/badge/Spring_Boot-3.4-6DB33F?style=for-the-badge&logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Gradle-8.x-02303A?style=for-the-badge&logo=gradle&logoColor=white">
  <img src="https://img.shields.io/badge/gRPC-1.76-2441E0?style=for-the-badge&logo=grpc&logoColor=white">
</p>

**Bridge any gRPC service to the MCP ecosystem** — auto-discovers methods via reflection or `.proto` files, generates JSON Schema for arguments, and exposes them as MCP tools with health monitoring, retry, and TLS support.

</div>

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [MCP Tools](#mcp-tools)
- [Personal Connections (per-client)](#personal-connections-per-client)
- [Discovery Modes](#discovery-modes)
- [Configuration](#configuration)
- [Architecture](#architecture)
- [Development](#development)
- [Docker](#docker)
- [Security](#security)
- [License](#license)

## Features

- **5 MCP tools** — `list_methods`, `get_method_schema`, `invoke_method`, `load_proto_file`, `health`
- **Personal connections** — each MCP client (opencode/Claude, …) connects to its own gRPC target via config headers, like `postgres-mcp`
- **2 discovery modes** — gRPC reflection or protoc-compiled `.proto` files (per connection, lazy)
- **In-memory proto cache** — pass a `.proto` file through the `load_proto_file` tool; it is compiled and cached in RAM per `host:port` + file name, shared across MCP sessions — nothing is written to the server disk
- **Resilience** — per-channel idle eviction, auto-rebuild on `UNAVAILABLE`, exponential-backoff retry
- **Security** — X-Api-Key authentication, TLS gRPC, custom metadata headers
- **Config-driven** — all settings via environment variables, single universal Docker image

## Requirements

- **Docker** — to run the image (Quick Start)
- **JDK 21 + Gradle** (the wrapper is included in the repo) — to build from source
- **protoc** — bundled in the Docker image; not required on the client machine

## Quick Start

```bash
docker run -p 8080:8080 \
  -e GRPC_ADDRESS=your-grpc-host:port \
  -e MCP_NAME="My Service" \
  -e MCP_API_KEY=your-api-key \
  grpc-mcp:1.6
```

`MCP_API_KEY` is optional — see [Security](#security).

Then connect any MCP client — standard `mcpServers` config (Claude Desktop, …):

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
| `list_methods` | Lists all discovered gRPC methods of the current connection as `ServiceName/methodName` |
| `get_method_schema` | JSON Schema of input arguments + example. Param: `method` |
| `invoke_method` | Call a gRPC method. Params: `method`, `arguments` (JSON), optional `metadata` (per-call headers) |
| `load_proto_file` | Loads a `.proto` file (content as a string argument) into the server-side RAM cache, compiles it and returns the method count + `cached` flag. No config changes needed |
| `health` | Current connection: channel state, method count |

## Personal Connections (per-client)

One container serves any number of clients, each with its own gRPC target — the same pattern as `postgres-mcp`.
Connection parameters are resolved **per HTTP request** with priority: **client header → container env → default**.

### Client headers (opencode.json)

| Header | Description                                                                                                                                                                                |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GRPC-Host` + `GRPC-Port` | gRPC target (port default `1234`)                                                                                                                                                          |
| `GRPC-Address` | Alternative: full `host:port` in one header                                                                                                                                                |
| `GRPC-Negotiation` | `PLAINTEXT` or `TLS`                                                                                                                                                                       |
| `GRPC-Metadata` | JSON string of static gRPC headers, e.g. `{"x-tenant":"acme"}`                                                                                                                             |
| `GRPC-ProtoFile` | Proto source: a file name in the proto base dir (file on the server) or an absolute/`~` path — a file on the client machine, loaded into the server-side proto cache via `load_proto_file` |

Non-ASCII header values (e.g. Cyrillic paths like `/home/me/Загрузки/svc.proto`) are supported:
the server re-decodes the UTF-8 bytes that HTTP clients put on the wire.

### Example (opencode.json)

```json
{
  "mcp": {
    "grpc-service-a": {
      "type": "remote",
      "url": "http://localhost:8080/mcp",
      "enabled": true,
      "oauth": false,
      "headers": {
        "X-API-Key": "<api-key>",
        "GRPC-Host": "0.0.0.1",
        "GRPC-Port": "1234",
        "GRPC-Negotiation": "TLS",
        "GRPC-Metadata": "{\"x-tenant\":\"acme\"}"
      }
    },
    "grpc-service-b": {
      "type": "remote",
      "url": "http://localhost:8080/mcp",
      "enabled": true,
      "oauth": false,
      "headers": {
        "X-API-Key": "<api-key>",
        "GRPC-Host": "0.0.0.2",
        "GRPC-ProtoFile": "/home/me/protos/svc.proto"
      }
    }
  }
}
```

Without `GRPC-*` headers the client falls back to the container env
(`GRPC_ADDRESS`, …) — the classic single-target deployment keeps working.

### Proto file on the client machine (in-memory, cached per connection)

If the target gRPC server has no reflection and the proto file lives on the client machine
(point `GRPC-ProtoFile` at it — e.g. `/home/me/protos/svc.proto`):

1. Call `list_methods` — the server replies with an instruction: read the file from the
   client machine and send its content via `load_proto_file` (if the file is missing at
   that path, report “file not found at the configured path”).
2. `load_proto_file(content="<file text>", name="svc.proto")` compiles the proto and keeps
   the methods **in the server-side RAM cache** — nothing is written to disk.
   - The cache is keyed by `host:port` + **proto file name without path**
     (`GRPC-Host`/`GRPC-Port` + base name of `GRPC-ProtoFile`), so different users sharing
     the same file at different local paths reuse the same cache entry.
   - Re-uploading the identical content returns `cached: true` without running `protoc`;
     changed content is recompiled and replaces the entry.
   - If the proto has `import` statements, upload the imported files first (dependencies
     first, the main proto with `service`/`rpc` last). A file without any service is kept
     as an import dependency (`methods: 0` — that's normal).
   - Well-known types need no upload: `google/protobuf/*` ships with the system protoc,
     and any other `google/**` import (`google/type/*`, `google/api/*`, `google/longrunning/*`,
     `google/rpc/*`) is extracted on demand from the `proto-google-common-protos` artifact
     into the WKT dir at compile time.
3. Call `list_methods` again — methods are immediately available. **No config changes needed.**

In the **next MCP session** (new session id) the methods are served from the cache
automatically — no `load_proto_file` call needed. The cache is Caffeine-backed:
entries live until idle > `GRPC_PROTO_CACHE_IDLE_TIMEOUT` (default 30 d) or LRU
eviction at `GRPC_PROTO_CACHE_MAX_ENTRIES` (default 64), and are cleared on app
restart. Stats are exposed via Micrometer (`cache.size`, `cache.gets`, `cache.puts`,
`cache.evictions` with `cache=protoCache` tag, when actuator metrics are enabled).
Max file size: `GRPC_MAX_UPLOAD_SIZE` (default 1 MB).

## Discovery Modes

Resolved **per connection** (lazy, on first use of the connection):

- **FILE** — `GRPC-ProtoFile` header / env is set → compiled via `protoc`, extracts schemas and source code comments
- **REFLECTION** — no proto file → uses gRPC `ServerReflectionService` (the server must support it)

## Configuration

### MCP

| Variable | Description | Default |
|---|---|---|
| `MCP_NAME` | MCP server display name | `grpc-mcp` |
| `MCP_API_KEY` | Auth key for `X-Api-Key` header; when empty/absent the server works without a key | (empty) |

### gRPC Connection (defaults, overridable per client via headers)

| Variable | Description | Default |
|---|---|---|
| `GRPC_ADDRESS` | gRPC target `host:port` (env fallback for clients without headers) | — |
| `GRPC_NEGOTIATION_TYPE` | `PLAINTEXT` or `TLS` | `PLAINTEXT` |
| `GRPC_PROTO_FILE` | Path to `.proto` file (empty = reflection) | — |
| `GRPC_METADATA` | JSON with extra gRPC headers | `{}` |

### Per-Client Connections

| Variable | Description | Default |
|---|---|---|
| `GRPC_PROTO_BASE_DIR` | Base dir for `GRPC-ProtoFile` file names (traversal-protected) | `./protos` |
| `GRPC_WKT_DIR` | Dir for well-known proto types (`protoc -I`); starts empty — unresolvable `google/**` imports are extracted here on demand from the classpath `proto-google-common-protos` artifact | `./wkt` |
| `GRPC_CONNECTION_IDLE_TIMEOUT` | Idle channels are closed after this period | `30m` |
| `GRPC_SWEEP_INTERVAL` | How often the background sweep runs (idle channel/proto eviction pass) | `5m` |
| `GRPC_PROTO_CACHE_IDLE_TIMEOUT` | Cached client-side protos are discarded after this idle period | `30d` |
| `GRPC_PROTO_CACHE_MAX_ENTRIES` | LRU cap for the proto cache; least recently used entries are evicted | `64` |
| `GRPC_MAX_UPLOAD_SIZE` | Max `.proto` size for `load_proto_file` (chars) | `1048576` |

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

## Architecture

```
┌──────────────┐      MCP (Streamable HTTP, /mcp)     ┌────────────────────┐
│              │  GRPC-Host/Port/Negotiation headers  │                    │   gRPC    ┌─────────────────┐
│  MCP Client  │ ───────────────────────────────────► │                    │ ────────► │ gRPC Service A  │
│  (opencode,  │  X-API-Key, GRPC-ProtoFile, …        │   per-client       │           └─────────────────┘
│  Claude, …)  │                                      │   channel cache    │   gRPC    ┌─────────────────┐
│              │  (each client entry in the config =  │   lazy discovery   │ ────────► │ gRPC Service B  │
│  second entry│   its own connection: target, creds) │   retry / evict    │           └─────────────────┘
└──────────────┘                                      │   load_proto_file  │
                                                       └────────────────────┘
```

- **Transport context extractor** resolves the per-client `GrpcConnection` on every request (headers → env → default)
- **Channel cache** — one `ManagedChannel` per `target|negotiation|metadata-hash`, idle channels evicted
- **Discovery cache** — one method set per connection (reflection or protoc), built lazily on first use
- **Proto cache** — client-supplied protos are compiled by `load_proto_file` and kept in RAM in a Caffeine cache, keyed by `host:port` + proto file name (shared across MCP sessions); evicted on idle timeout, LRU cap or shutdown (never persisted)

## Development

```bash
# Build: compile + all tests + JaCoCo verification (80% line-coverage gate)
./gradlew build

# Tests only
./gradlew test

# Run locally without Docker
./gradlew bootRun
```

Coverage report after `./gradlew build`: `build/reports/jacoco/test/html`.

## Docker

```bash
docker build -t grpc-mcp:1.6 .
```

Run the built image as shown in [Quick Start](#quick-start).

## Security

- When `MCP_API_KEY` is set, MCP requests require a matching `X-Api-Key` header; otherwise `401 Unauthorized`
- When `MCP_API_KEY` is empty/absent, the server works without a key (open access); `/actuator` is always open
- gRPC connection supports TLS via `GRPC_NEGOTIATION_TYPE=TLS`
- Custom gRPC metadata headers configured via `GRPC_METADATA` JSON

## License

[MIT](LICENSE) © 2026 KSabuev

---

<div align="center">
  <sub>Built with Spring Boot, gRPC-Java, and the Model Context Protocol SDK</sub>
</div>
