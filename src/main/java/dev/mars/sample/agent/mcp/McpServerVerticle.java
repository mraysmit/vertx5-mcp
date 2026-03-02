package dev.mars.sample.agent.mcp;

import dev.mars.sample.agent.runner.AgentContext;
import dev.mars.sample.agent.tool.Tool;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP (Model Context Protocol) server verticle that exposes the agent's
 * tools over the <b>HTTP + SSE transport</b>.
 *
 * <h2>Transport</h2>
 * The server implements the MCP HTTP+SSE transport:
 * <ul>
 *   <li>{@code GET <basePath>/sse} — the client opens a long-lived SSE
 *       connection. The server immediately sends an {@code endpoint}
 *       event containing the URL for posting JSON-RPC messages.</li>
 *   <li>{@code POST <basePath>/message?sessionId=<id>} — the client
 *       sends JSON-RPC 2.0 requests here. The server writes responses
 *       back on the matching SSE stream and returns {@code 202 Accepted}
 *       on the POST.</li>
 * </ul>
 *
 * <h2>Supported JSON-RPC methods</h2>
 * <table>
 *   <tr><th>Method</th><th>Description</th></tr>
 *   <tr><td>{@code initialize}</td><td>Capability negotiation handshake</td></tr>
 *   <tr><td>{@code ping}</td><td>Health check (returns empty result)</td></tr>
 *   <tr><td>{@code tools/list}</td><td>Returns all registered tools with
 *       name, description, and inputSchema</td></tr>
 *   <tr><td>{@code tools/call}</td><td>Invokes a tool by name with the
 *       supplied arguments</td></tr>
 * </table>
 *
 * <h2>Notifications</h2>
 * JSON-RPC notifications (messages without an {@code id}) such as
 * {@code notifications/initialized} are accepted and logged but produce
 * no response, per the JSON-RPC 2.0 specification.
 *
 * <h2>Configuration (Vert.x config)</h2>
 * <ul>
 *   <li>{@code mcp.port} — TCP port for the MCP HTTP server
 *       (default {@code 3001})</li>
 *   <li>{@code mcp.basePath} — URL path prefix for MCP endpoints
 *       (default {@code ""})</li>
 * </ul>
 *
 * <h2>Tool invocation context</h2>
 * When a tool is called via MCP (outside the normal agent loop), a
 * lightweight {@link AgentContext} is synthesised with a generated
 * correlation ID and a case ID derived from the tool arguments
 * (falling back to the correlation ID if no domain identifier is
 * present).
 *
 * @see Tool
 * @see dev.mars.sample.agent.tool.ToolRegistry
 */
public class McpServerVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(McpServerVerticle.class.getName());

  /** MCP protocol version supported by this server. */
  static final String PROTOCOL_VERSION = "2024-11-05";
  static final String SERVER_NAME = "vertx5-agent-sample";
  static final String SERVER_VERSION = "0.1.0";

  // JSON-RPC 2.0 error codes
  private static final int ERR_METHOD_NOT_FOUND = -32601;
  private static final int ERR_INVALID_PARAMS   = -32602;
  private static final int ERR_INTERNAL          = -32603;

  private static final int DEFAULT_MCP_PORT = 3001;

  private final Map<String, Tool> tools;
  private final String caseIdField;

  /** Active SSE sessions: sessionId → response. */
  private final Map<String, HttpServerResponse> sessions = new ConcurrentHashMap<>();

  /** Actual TCP port after successful listen (−1 until started). */
  private volatile int actualPort = -1;

  /**
   * Creates a new MCP server verticle.
   *
   * @param tools       the allow-listed tool map (name → tool) to expose
   * @param caseIdField the JSON field name used to extract a case identifier
   *                    from tool arguments (e.g. {@code "tradeId"})
   */
  public McpServerVerticle(Map<String, Tool> tools, String caseIdField) {
    this.tools = Map.copyOf(tools);
    this.caseIdField = caseIdField;
  }

  /**
   * Returns the actual TCP port after successful start, or {@code −1}
   * if the server has not started yet.
   */
  int actualPort() { return actualPort; }

  @Override
  public void start(Promise<Void> startPromise) {
    int port = config().getInteger("mcp.port", DEFAULT_MCP_PORT);
    String basePath = config().getString("mcp.basePath", "");

    Router router = Router.router(vertx);
    router.route().handler(BodyHandler.create());

    router.get(basePath + "/sse").handler(this::handleSse);
    router.post(basePath + "/message").handler(this::handleMessage);

    vertx.createHttpServer()
      .requestHandler(router)
      .listen(port)
      .onSuccess(server -> {
        actualPort = server.actualPort();
        LOG.info("MCP server started on port " + server.actualPort()
            + " (basePath=\"" + basePath + "\")");
        startPromise.complete();
      })
      .onFailure(startPromise::fail);
  }

  // ── SSE connection ──────────────────────────────────────────────────

  private void handleSse(RoutingContext ctx) {
    String sessionId = UUID.randomUUID().toString();
    HttpServerResponse response = ctx.response();

    response.setChunked(true)
      .putHeader("Content-Type", "text/event-stream")
      .putHeader("Cache-Control", "no-cache")
      .putHeader("Connection", "keep-alive")
      .putHeader("Access-Control-Allow-Origin", "*");

    sessions.put(sessionId, response);
    response.closeHandler(v -> {
      sessions.remove(sessionId);
      LOG.fine("MCP session closed: " + sessionId);
    });

    // Tell the client where to POST JSON-RPC messages
    String basePath = config().getString("mcp.basePath", "");
    String messageUrl = basePath + "/message?sessionId=" + sessionId;
    response.write("event: endpoint\ndata: " + messageUrl + "\n\n");

    LOG.info("MCP session opened: " + sessionId);
  }

  // ── JSON-RPC message handling ───────────────────────────────────────

  private void handleMessage(RoutingContext ctx) {
    String sessionId = ctx.request().getParam("sessionId");
    HttpServerResponse sseResponse = sessionId != null ? sessions.get(sessionId) : null;

    if (sseResponse == null) {
      LOG.warning("Invalid or missing sessionId: " + sessionId);
      ctx.response().setStatusCode(400)
        .putHeader("content-type", "application/json")
        .end(new JsonObject().put("error", "Invalid or missing sessionId").encode());
      return;
    }

    JsonObject request = ctx.body().asJsonObject();
    if (request == null) {
      ctx.response().setStatusCode(400)
        .putHeader("content-type", "application/json")
        .end(new JsonObject().put("error", "Expected JSON body").encode());
      return;
    }

    String method = request.getString("method", "");
    Object id = request.getValue("id");
    JsonObject params = request.getJsonObject("params", new JsonObject());

    // JSON-RPC notifications (no id) — acknowledge without response
    if (id == null) {
      LOG.fine("MCP notification received: " + method);
      ctx.response().setStatusCode(202).end();
      return;
    }

    // Dispatch the JSON-RPC request
    LOG.info("MCP request: method=" + method + " id=" + id + " sessionId=" + sessionId);
    dispatch(method, params)
      .onSuccess(result -> {
        LOG.fine("MCP response sent for method=" + method + " id=" + id);
        JsonObject rpcResponse = new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("result", result);
        sseResponse.write("event: message\ndata: " + rpcResponse.encode() + "\n\n");
        ctx.response().setStatusCode(202).end();
      })
      .onFailure(err -> {
        int code = errorCodeFor(err);
        LOG.warning("MCP error for method=" + method + " id=" + id + ": code=" + code + " message=" + err.getMessage());
        JsonObject rpcError = new JsonObject()
          .put("jsonrpc", "2.0")
          .put("id", id)
          .put("error", new JsonObject()
            .put("code", code)
            .put("message", err.getMessage()));
        sseResponse.write("event: message\ndata: " + rpcError.encode() + "\n\n");
        ctx.response().setStatusCode(202).end();
      });
  }

  // ── Method dispatch ─────────────────────────────────────────────────

  private Future<JsonObject> dispatch(String method, JsonObject params) {
    return switch (method) {
      case "initialize"  -> {
        LOG.info("MCP initialize handshake: protocolVersion=" + PROTOCOL_VERSION);
        yield handleInitialize();
      }
      case "ping"        -> {
        LOG.fine("MCP ping received");
        yield Future.succeededFuture(new JsonObject());
      }
      case "tools/list"  -> {
        LOG.info("MCP tools/list: returning " + tools.size() + " tool(s)");
        yield handleToolsList();
      }
      case "tools/call"  -> handleToolsCall(params);
      default            -> {
        LOG.warning("MCP unknown method: " + method);
        yield Future.failedFuture(
            new MethodNotFoundException("Method not found: " + method));
      }
    };
  }

  private Future<JsonObject> handleInitialize() {
    return Future.succeededFuture(new JsonObject()
      .put("protocolVersion", PROTOCOL_VERSION)
      .put("serverInfo", new JsonObject()
        .put("name", SERVER_NAME)
        .put("version", SERVER_VERSION))
      .put("capabilities", new JsonObject()
        .put("tools", new JsonObject().put("listChanged", false))));
  }

  private Future<JsonObject> handleToolsList() {
    JsonArray toolList = new JsonArray();
    for (Tool tool : tools.values()) {
      toolList.add(new JsonObject()
        .put("name", tool.name())
        .put("description", tool.description())
        .put("inputSchema", tool.schema()));
    }
    return Future.succeededFuture(new JsonObject().put("tools", toolList));
  }

  private Future<JsonObject> handleToolsCall(JsonObject params) {
    String toolName = params.getString("name");
    if (toolName == null || toolName.isBlank()) {
      LOG.warning("MCP tools/call: missing required parameter 'name'");
      return Future.failedFuture(
          new InvalidParamsException("Missing required parameter: name"));
    }

    Tool tool = tools.get(toolName);
    if (tool == null) {
      LOG.warning("MCP tool not found: " + toolName + " (available: " + tools.keySet() + ")");
      return Future.failedFuture(
          new InvalidParamsException(
              "Tool not found: " + toolName + " (not allowlisted)"));
    }

    JsonObject args = params.getJsonObject("arguments", new JsonObject());
    String correlationId = UUID.randomUUID().toString();
    String caseId = args.getString(caseIdField, "mcp-" + correlationId);
    AgentContext ctx = new AgentContext(correlationId, caseId, new JsonObject());

    LOG.info("MCP tools/call: tool=" + toolName + " caseId=" + caseId);

    return tool.invoke(args, ctx)
      .map(result -> new JsonObject()
        .put("content", new JsonArray()
          .add(new JsonObject()
            .put("type", "text")
            .put("text", result.encodePrettily()))));
  }

  // ── Error classification ────────────────────────────────────────────

  private int errorCodeFor(Throwable err) {
    if (err instanceof MethodNotFoundException) return ERR_METHOD_NOT_FOUND;
    if (err instanceof InvalidParamsException)  return ERR_INVALID_PARAMS;
    return ERR_INTERNAL;
  }

  /** Marker exception for JSON-RPC "method not found" errors. */
  static final class MethodNotFoundException extends RuntimeException {
    MethodNotFoundException(String msg) { super(msg); }
  }

  /** Marker exception for JSON-RPC "invalid params" errors. */
  static final class InvalidParamsException extends RuntimeException {
    InvalidParamsException(String msg) { super(msg); }
  }
}
