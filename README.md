# Vert.x 5.x Agent vs Workflow Sample (Java)

A **minimal, runnable Vert.x 5 sample** showing the correct hybrid pattern:

- **Deterministic processor** handles known failures via pluggable handlers.
- **Agent boundary** only triggers when the failure is unknown/ambiguous.
- The "LLM" is a **stub** that emits strict JSON commands (replace later).
- **Tools are self-describing** with JSON Schema metadata aligned with MCP.

## Requirements
- Java 21+
- Maven 3.9+

## Build + Run
```bash
mvn -q test
mvn -q package
java -jar target/vertx5-agent-sample-0.1.0-SNAPSHOT-fat.jar
```

Server starts on **http://localhost:8080** (override with config `http.port`)

MCP server starts on **http://localhost:3001** (override with `mcp.port` in `pipeline.yaml`)

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

### 3) MCP — list available tools
```bash
# Open SSE connection and note the sessionId from the endpoint event
curl -N http://localhost:3001/sse &

# Send tools/list (replace <sessionId> with value from endpoint event)
curl -s -X POST "http://localhost:3001/message?sessionId=<sessionId>" \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### 4) MCP — invoke a tool directly
```bash
curl -s -X POST "http://localhost:3001/message?sessionId=<sessionId>" \
  -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"case.raiseTicket","arguments":{"tradeId":"T-300","category":"ReferenceData","summary":"MCP test"}}}'
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

The project includes a built-in **MCP server** (`McpServerVerticle`) that
exposes the agent's tools via the [Model Context Protocol](https://modelcontextprotocol.io/)
using the **HTTP + SSE transport**.

### Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/sse` | GET | Opens an SSE connection; server sends an `endpoint` event with the URL for posting messages |
| `/message?sessionId=<id>` | POST | Receives JSON-RPC 2.0 requests; responses are delivered via the SSE stream |

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
    "vertx5-agent-sample": {
      "transport": "sse",
      "url": "http://localhost:3001/sse"
    }
  }
}
```

---

## How This Demonstrates MCP

This project implements MCP directly — the `McpServerVerticle` exposes
tools via the standard MCP HTTP+SSE transport with JSON-RPC 2.0. The
core abstractions map onto MCP concepts:

### MCP Concept Mapping

| MCP Concept | This Project | Where |
|---|---|---|
| **Tool** — a callable capability with a name, description, and input schema | `Tool` interface with `name()`, `description()`, `schema()` | `tool/Tool.java` |
| **`tools/list`** — server advertises available tools with their schemas | `ToolRegistry` holds all registered tools; each tool self-describes via `schema()` | `tool/ToolRegistry.java`, `mcp/McpServerVerticle.java` |
| **`tools/call`** — client invokes a tool by name with JSON arguments | `AgentRunnerVerticle.executeCommand()` and `McpServerVerticle.handleToolsCall()` dispatch `{tool, args}` to the matching `Tool` | `runner/AgentRunnerVerticle.java`, `mcp/McpServerVerticle.java` |
| **`inputSchema`** — JSON Schema describing a tool's parameters | `Tool.schema()` returns JSON Schema 2020-12 compatible `JsonObject` | `tool/Tool.java` |
| **Tool allow-listing** — security boundary controlling which tools an agent can use | `ToolRegistry` acts as the allow-list; unknown tools are rejected with "not allowlisted" | `runner/AgentRunnerVerticle.java` |
| **LLM function-calling** — LLM decides which tool to call and with what arguments | `LlmClient.decideNext()` returns `{intent: "CALL_TOOL", tool, args}` commands | `llm/LlmClient.java` |
| **Agent loop** — iterative tool calls until the task is complete | `AgentRunnerVerticle.runLoop()` loops until `stop: true` or step limit | `runner/AgentRunnerVerticle.java` |

### What Each Tool Exposes (MCP-Ready)

Every `Tool` implementation provides three pieces of metadata that an MCP
server would advertise in a `tools/list` response:

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

The MCP server covers tools. To complete the full MCP surface:

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
   serves `tools/list` and `tools/call` over HTTP+SSE.

3. **Replace `StubLlmClient`** — use `OpenAiLlmClient` (placeholder in
   `llm/OpenAiLlmClient.java`) with function-calling, passing tool schemas
   as the function definitions.

---

## What to Look At

| Component | Purpose |
|---|---|
| `DeterministicFailureProcessorVerticle` | Normal service — fast, predictable, strategy-pattern handlers |
| `AgentRunnerVerticle` | Step-limited agent runner + tool dispatch (MCP client pattern) |
| `McpServerVerticle` | MCP server — exposes tools via HTTP+SSE transport |
| `Tool` interface | Self-describing tools with `name()`, `description()`, `schema()` |
| `StubLlmClient` | Rule-based stub returning strict JSON commands |
| `pipeline.yaml` | All configuration externalised — addresses, handlers, tools, LLM |
| `config/` package | Records, loader, and factory classes for YAML-driven wiring |

## Configuration

All pipeline wiring is in `src/main/resources/pipeline.yaml`. Override the
config file path at startup:

```bash
java -Dpipeline.config=my-config.yaml -jar app.jar
```

See `PipelineConfigLoader` for loading details and `MainVerticle` for how
factories resolve YAML aliases to concrete classes.

## Test Coverage

120 tests across 24 test classes covering:
- Config record validation and defensive copying
- Factory resolution (handlers, tools, LLM clients) with error cases
- YAML config parsing and missing-resource handling
- Handler behaviour and event bus publishing
- Tool invocation, schema metadata, and registry immutability
- StubLlmClient rule matching, fallback, and defensive copying
- InMemoryMemoryStore lifecycle and case isolation
- Verticle deployment, routing, step limits, and error paths
- MCP server SSE transport, JSON-RPC dispatch, tool invocation, and error handling
- End-to-end smoke tests (deterministic + agent paths)

## Logging

The application uses `java.util.logging` (JUL) configured via
`src/main/resources/logging.properties`, loaded by `MainVerticle` at
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
java -Djava.util.logging.config.file=my-logging.properties -jar target/vertx5-agent-sample-0.1.0-SNAPSHOT.jar
```
