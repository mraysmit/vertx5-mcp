# vertx5-mcp — Vert.x 5 Agent + MCP Server (Java)

A **multi-module Vert.x 5 project** demonstrating the hybrid deterministic-workflow / LLM-agent pattern with a built-in [Model Context Protocol](https://modelcontextprotocol.io/) (MCP) server.

- **Deterministic processor** handles known failures via pluggable handlers.
- **Agent boundary** only triggers when the failure is unknown/ambiguous.
- The "LLM" is a **stub** that emits strict JSON commands (replace later).
- **Tools are self-describing** with JSON Schema metadata aligned with MCP.
- **Built-in MCP server** exposes tools via Streamable HTTP (2025-03-26) and legacy HTTP+SSE (2024-11-05) transports.

## Modules

| Module | ArtifactId | Purpose |
|---|---|---|
| `mcp-server` | `mcp-server` | Standalone MCP server verticle — Streamable HTTP + legacy SSE transports, `Tool` / `ToolRegistry` interfaces. Zero agent coupling. |
| `agent-core` | `agent-core` | Reusable agent infrastructure — LLM interfaces, memory, agent runner, deterministic processor, config records, HTTP API verticle. |
| `agent-app` | `agent-app` | Trade-failure domain logic — handlers, tools, factories, YAML-driven bootstrap (`MainVerticle`). Produces the fat JAR. |

```
vertx5-mcp/
├── mcp-server/         # dev.mars.mcp
├── agent-core/         # dev.mars.agent  (core abstractions)
├── agent-app/          # dev.mars.agent  (domain + bootstrap)
└── pom.xml             # parent POM (dev.mars:vertx5-agent)
```

## Requirements
- Java 21+
- Maven 3.9+

## Build + Run
```bash
mvn -q clean test
mvn -q package -DskipTests
java -jar agent-app/target/agent-app-0.1.0-SNAPSHOT-fat.jar
```

- Agent HTTP API: **http://localhost:8080** (override with `http.port` in `pipeline.yaml`)
- MCP server: **http://localhost:3001** (override with `mcp.port` in `pipeline.yaml`)

## Health Check
```bash
curl http://localhost:8080/health
```

## Try it

### 1) Known deterministic workflow (no agent)
```bash
curl -s -X POST http://localhost:8080/trade/failures \
  -H 'content-type: application/json' \
  -d '{"tradeId":"T-100","reason":"Missing ISIN"}' | jq
```

### 2) Unknown failure (agent classification kicks in)
```bash
curl -s -X POST http://localhost:8080/trade/failures \
  -H 'content-type: application/json' \
  -d '{"tradeId":"T-200","reason":"LEI not found in registry"}' | jq
```

### 3) MCP — Streamable HTTP transport (2025-03-26)

```bash
# Initialize a session
curl -s -X POST http://localhost:3001/mcp \
  -H 'content-type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

# List tools (include Mcp-Session-Id from the initialize response)
curl -s -X POST http://localhost:3001/mcp \
  -H 'content-type: application/json' \
  -H 'Accept: application/json' \
  -H 'Mcp-Session-Id: <sessionId>' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# Invoke a tool
curl -s -X POST http://localhost:3001/mcp \
  -H 'content-type: application/json' \
  -H 'Accept: application/json' \
  -H 'Mcp-Session-Id: <sessionId>' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"case.raiseTicket","arguments":{"tradeId":"T-300","category":"ReferenceData","summary":"MCP test"}}}'
```

### 4) MCP — Legacy SSE transport (2024-11-05)
```bash
# Open SSE connection and note the sessionId from the endpoint event
curl -N http://localhost:3001/sse &

# Send tools/list (replace <sessionId> with value from endpoint event)
curl -s -X POST "http://localhost:3001/message?sessionId=<sessionId>" \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

---

## Architecture

```
HTTP POST ──► HttpApiVerticle ──► DeterministicFailureProcessorVerticle
                                        │
                         ┌──────────────┴──────────────┐
                  Known reason?                  Unknown reason?
                         │                              │
                  FailureHandler                 AgentRunnerVerticle
                  (lookup-enrich,                       │
                   escalate, ...)              LlmClient.decideNext()
                         │                              │
                  Returns result              ┌─────────▼─────────┐
                                              │  { intent, tool,  │
                                              │    args, stop }   │
                                              └─────────┬─────────┘
                                                        │
                                               Tool.invoke(args, ctx)
                                                        │
                                              EventSinkVerticle (events.out)
```

All wiring — addresses, handlers, tools, LLM client, MCP server — is
externalised to `pipeline.yaml` and resolved at startup via factory classes.

---

## MCP Server

The `mcp-server` module provides `McpServerVerticle` — a standalone MCP server
that exposes the agent's tools via the [Model Context Protocol](https://modelcontextprotocol.io/).

### Transports

| Transport | Spec Version | Endpoints |
|---|---|---|
| **Streamable HTTP** | 2025-03-26 | `POST /mcp`, `GET /mcp`, `DELETE /mcp` |
| **Legacy HTTP+SSE** | 2024-11-05 | `GET /sse`, `POST /message?sessionId=<id>` |

### Streamable HTTP (2025-03-26)

The primary transport. A single `/mcp` endpoint handles all interactions:
- **POST** — JSON-RPC requests/notifications. Responds with `application/json` or `text/event-stream` based on the client's `Accept` header. Supports JSON-RPC batch requests.
- **GET** — Opens an SSE stream for server-initiated messages.
- **DELETE** — Terminates the session.

Session management uses the `Mcp-Session-Id` header — assigned after `initialize`, required on all subsequent requests.

### Legacy HTTP+SSE (2024-11-05)

Backwards-compatible transport for older MCP clients:
- **GET `/sse`** — Opens an SSE connection; server sends an `endpoint` event with the URL for posting messages.
- **POST `/message?sessionId=<id>`** — Receives JSON-RPC requests; responses are delivered via the SSE stream.

### Supported JSON-RPC Methods

| Method | Description |
|---|---|
| `initialize` | Capability negotiation — returns protocol version, server info, and capabilities |
| `ping` | Health check — returns empty result |
| `tools/list` | Returns all registered tools with name, description, and `inputSchema` |
| `tools/call` | Invokes a tool by name with the supplied arguments |

### Configuration

```yaml
mcp:
  enabled: true    # set to false to disable the MCP server
  port: 3001       # TCP port (0 for random in tests)
  basePath: ""     # URL prefix for MCP endpoints
```

### Connecting from Claude Desktop

Add this to your Claude Desktop MCP settings:
```json
{
  "mcpServers": {
    "vertx5-mcp": {
      "transport": "sse",
      "url": "http://localhost:3001/sse"
    }
  }
}
```

---

## How This Demonstrates MCP

This project implements MCP directly — `McpServerVerticle` exposes tools via
standard MCP transports with JSON-RPC 2.0. The core abstractions map onto MCP
concepts:

### MCP Concept Mapping

| MCP Concept | This Project | Where |
|---|---|---|
| **Tool** — a callable capability with a name, description, and input schema | `Tool` interface with `name()`, `description()`, `schema()` | `mcp-server` — `tool/Tool.java` |
| **`tools/list`** — server advertises available tools with their schemas | `ToolRegistry` holds all registered tools; each tool self-describes via `schema()` | `mcp-server` — `tool/ToolRegistry.java` |
| **`tools/call`** — client invokes a tool by name with JSON arguments | `McpServerVerticle` dispatches `{tool, args}` to the matching `Tool` | `mcp-server` — `McpServerVerticle.java` |
| **`inputSchema`** — JSON Schema describing a tool's parameters | `Tool.schema()` returns JSON Schema 2020-12 compatible `JsonObject` | `mcp-server` — `tool/Tool.java` |
| **Tool allow-listing** — security boundary controlling which tools an agent can use | `ToolRegistry` acts as the allow-list; unknown tools are rejected | `mcp-server` — `tool/ToolRegistry.java` |
| **LLM function-calling** — LLM decides which tool to call and with what arguments | `LlmClient.decideNext()` returns `{intent, tool, args}` commands | `agent-core` — `llm/LlmClient.java` |
| **Agent loop** — iterative tool calls until the task is complete | `AgentRunnerVerticle.runLoop()` loops until `stop: true` or step limit | `agent-core` — `runner/AgentRunnerVerticle.java` |

### What Each Tool Exposes (MCP-Ready)

Every `Tool` implementation provides three pieces of metadata that an MCP
server advertises in a `tools/list` response:

```java
public interface Tool {
    String name();             // MCP tool name
    String description();      // MCP tool description
    JsonObject schema();       // MCP inputSchema (JSON Schema)
    Future<JsonObject> invoke(JsonObject args, AgentContext ctx);  // MCP tools/call
}
```

For example, `RaiseTicketTool` exposes:

```json
{
  "name": "case.raiseTicket",
  "description": "Creates a support ticket for a trade failure requiring manual investigation.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "tradeId":  { "type": "string", "description": "The trade identifier" },
      "category": { "type": "string", "description": "Ticket category, e.g. ReferenceData" },
      "summary":  { "type": "string", "description": "Brief summary of the issue" },
      "detail":   { "type": "string", "description": "Detailed description of the failure" }
    },
    "required": ["tradeId", "category", "summary"]
  }
}
```

### The Command Protocol

The LLM (or stub) communicates with the agent runner using a simple JSON
command format that mirrors MCP's `tools/call` request:

```json
{
  "intent": "CALL_TOOL",
  "tool": "case.raiseTicket",
  "args": {
    "tradeId": "T-200",
    "category": "ReferenceData",
    "summary": "Counterparty LEI issue"
  },
  "stop": true
}
```

| Field | Purpose | MCP Equivalent |
|---|---|---|
| `intent` | Action type (only `CALL_TOOL` supported) | Implicit in `tools/call` |
| `tool` | Tool name to invoke | `tools/call` → `name` |
| `args` | Tool arguments | `tools/call` → `arguments` |
| `stop` | Whether to end the agent loop | Application-level control |

### What's Remaining for Full MCP

| Gap | What's Needed |
|---|---|
| **Resources** | MCP resources for exposing contextual data (e.g. trade details) |
| **Prompts** | MCP prompt templates for structured LLM interactions |
| **External tool servers** | Connect to out-of-process MCP servers instead of in-process `Tool` instances |

The key architectural decision — that **the runner does not choose tools** but
instead validates and dispatches commands from an external decision-maker (LLM)
— is exactly the MCP client pattern. Swapping `StubLlmClient` for a real LLM
with MCP-compatible function-calling requires no changes to the tool layer.

### Path to Further MCP Adoption

1. **Add an `McpToolAdapter`** — a `Tool` implementation that wraps an MCP
   server connection, translating `invoke()` calls into MCP `tools/call`
   JSON-RPC requests. This lets external MCP tools sit alongside in-process
   tools transparently.

2. ~~**Expose tools as an MCP server**~~ — ✅ Done. `McpServerVerticle`
   serves `tools/list` and `tools/call` over Streamable HTTP and legacy SSE.

3. **Replace `StubLlmClient`** — use `OpenAiLlmClient` (placeholder in
   `llm/OpenAiLlmClient.java`) with function-calling, passing tool schemas
   as the function definitions.

---

## What to Look At

| Component | Module | Purpose |
|---|---|---|
| `McpServerVerticle` | `mcp-server` | MCP server — Streamable HTTP + legacy SSE transports |
| `Tool` interface | `mcp-server` | Self-describing tools with `name()`, `description()`, `schema()` |
| `ToolRegistry` | `mcp-server` | Tool registration and allow-listing |
| `DeterministicFailureProcessorVerticle` | `agent-core` | Normal service — fast, predictable, strategy-pattern handlers |
| `AgentRunnerVerticle` | `agent-core` | Step-limited agent runner + tool dispatch (MCP client pattern) |
| `HttpApiVerticle` | `agent-core` | HTTP ingress with schema validation |
| `StubLlmClient` | `agent-core` | Rule-based stub returning strict JSON commands |
| `MainVerticle` | `agent-app` | Application bootstrap — deploys all verticles from YAML config |
| `pipeline.yaml` | `agent-app` | All configuration externalised — addresses, handlers, tools, LLM, MCP |
| `config/` package | `agent-app` | Factory classes for YAML-driven wiring |

## Configuration

All pipeline wiring is in `agent-app/src/main/resources/pipeline.yaml`. Override the
config file path at startup:

```bash
java -Dpipeline.config=my-config.yaml -jar agent-app/target/agent-app-0.1.0-SNAPSHOT-fat.jar
```

See `PipelineConfigLoader` for loading details and `MainVerticle` for how
factories resolve YAML aliases to concrete classes.

## Test Coverage

24 test classes across all three modules covering:

**mcp-server**
- `ToolRegistry` immutability, lookup, and registration
- `McpServerVerticle` — Streamable HTTP transport, legacy SSE transport, JSON-RPC dispatch, session management, batch requests, tool invocation, and error handling

**agent-core**
- Config record validation and defensive copying (`HttpConfig`, `McpConfig`, `LlmConfig`, `HandlerConfig`, `SchemaConfig`)
- `StubLlmClient` rule matching, fallback, and defensive copying
- `OpenAiLlmClient` placeholder
- `InMemoryMemoryStore` lifecycle and case isolation
- `DeterministicFailureProcessorVerticle` routing and handler dispatch
- `AgentRunnerVerticle` step limits, tool dispatch, and error paths
- `HttpApiVerticle` routing and request validation
- `EventSinkVerticle` event bus publishing

**agent-app**
- Factory resolution (handlers, tools, LLM clients) with error cases
- YAML config parsing and missing-resource handling
- Handler behaviour (`LookupEnrichHandler`, `EscalateHandler`)
- Tool invocation and schema metadata (`RaiseTicketTool`, `PublishEventTool`)
- `TradeFailureRuleLoader` rule loading
- End-to-end smoke tests (deterministic + agent paths)

## Logging

The application uses `java.util.logging` (JUL) configured via
`agent-app/src/main/resources/logging.properties`, loaded by `MainVerticle` at
startup.

| Setting | Value |
|---------|-------|
| Console | `INFO` to `System.err` |
| File | Rolling files at `logs/vertx-agent-%g.log` |
| Max file size | 10 MB |
| Retained files | 10 |
| Format | ISO-8601 timestamp, e.g. `2026-03-02T10:08:01.937+0800 INFO [class] message` |

Log files are written to the `logs/` directory (created automatically).
Override at runtime with a system property:

```bash
java -Djava.util.logging.config.file=my-logging.properties -jar agent-app/target/agent-app-0.1.0-SNAPSHOT-fat.jar
```
