package dev.mars.sample.agent.mcp;

import dev.mars.sample.agent.tool.PublishEventTool;
import dev.mars.sample.agent.tool.RaiseTicketTool;
import dev.mars.sample.agent.tool.Tool;
import dev.mars.sample.agent.tool.ToolRegistry;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link McpServerVerticle}.
 *
 * <p>Each test deploys the verticle on port 0 (random) and interacts
 * via the HTTP + SSE transport: first opening an SSE connection to
 * discover the message endpoint, then posting JSON-RPC requests.
 */
@ExtendWith(VertxExtension.class)
class McpServerVerticleTest {

  private static final AtomicInteger SEQ = new AtomicInteger();
  private Map<String, Tool> tools;

  @BeforeEach
  void setUp(Vertx vertx) {
    String eventsAddr = "test.events." + SEQ.incrementAndGet();
    vertx.eventBus().consumer(eventsAddr, msg -> {}); // sink
    tools = ToolRegistry.of(
        new PublishEventTool(vertx, eventsAddr),
        new RaiseTicketTool(vertx, eventsAddr)
    );
  }

  private DeploymentOptions portZero() {
    return new DeploymentOptions().setConfig(
        new JsonObject().put("mcp.port", 0));
  }

  // ── Deployment ────────────────────────────────────────────────────

  @Test
  void deploys_successfully(Vertx vertx, VertxTestContext ctx) {
    vertx.deployVerticle(new McpServerVerticle(tools, "tradeId"), portZero())
      .onSuccess(id -> ctx.completeNow())
      .onFailure(ctx::failNow);
  }

  // ── SSE connection ────────────────────────────────────────────────

  @Test
  void sse_endpoint_sends_endpoint_event(Vertx vertx, VertxTestContext ctx) {
    var verticle = new McpServerVerticle(tools, "tradeId");

    vertx.deployVerticle(verticle, portZero())
      .compose(id -> vertx.createHttpClient()
        .request(HttpMethod.GET, verticle.actualPort(), "localhost", "/sse"))
      .compose(HttpClientRequest::send)
      .onSuccess(response -> {
        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream",
            response.getHeader("Content-Type"));

        response.handler(buffer -> ctx.verify(() -> {
          String data = buffer.toString();
          if (data.contains("event: endpoint")) {
            assertTrue(data.contains("/message?sessionId="));
            ctx.completeNow();
          }
        }));
      })
      .onFailure(ctx::failNow);
  }

  // ── Invalid message endpoint ──────────────────────────────────────

  @Test
  void message_without_session_returns_400(Vertx vertx, VertxTestContext ctx) {
    var verticle = new McpServerVerticle(tools, "tradeId");

    vertx.deployVerticle(verticle, portZero())
      .compose(id -> vertx.createHttpClient()
        .request(HttpMethod.POST, verticle.actualPort(), "localhost",
            "/message"))
      .compose(req -> req
        .putHeader("content-type", "application/json")
        .send(Buffer.buffer(new JsonObject()
          .put("jsonrpc", "2.0").put("id", 1)
          .put("method", "ping").encode())))
      .onSuccess(resp -> ctx.verify(() -> {
        assertEquals(400, resp.statusCode());
        ctx.completeNow();
      }))
      .onFailure(ctx::failNow);
  }

  @Test
  void message_with_invalid_session_returns_400(Vertx vertx, VertxTestContext ctx) {
    var verticle = new McpServerVerticle(tools, "tradeId");

    vertx.deployVerticle(verticle, portZero())
      .compose(id -> vertx.createHttpClient()
        .request(HttpMethod.POST, verticle.actualPort(), "localhost",
            "/message?sessionId=nonexistent"))
      .compose(req -> req
        .putHeader("content-type", "application/json")
        .send(Buffer.buffer(new JsonObject()
          .put("jsonrpc", "2.0").put("id", 1)
          .put("method", "ping").encode())))
      .onSuccess(resp -> ctx.verify(() -> {
        assertEquals(400, resp.statusCode());
        ctx.completeNow();
      }))
      .onFailure(ctx::failNow);
  }

  // ── Full SSE + message round-trip tests ───────────────────────────

  @Test
  void initialize_returns_server_info(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "initialize")
        .put("params", new JsonObject()
          .put("protocolVersion", "2024-11-05")
          .put("clientInfo", new JsonObject()
            .put("name", "test").put("version", "1.0"))),
      response -> {
        assertEquals("2.0", response.getString("jsonrpc"));
        assertEquals(1, response.getInteger("id"));
        JsonObject result = response.getJsonObject("result");
        assertNotNull(result);
        assertEquals(McpServerVerticle.PROTOCOL_VERSION,
            result.getString("protocolVersion"));
        assertEquals(McpServerVerticle.SERVER_NAME,
            result.getJsonObject("serverInfo").getString("name"));
        assertNotNull(result.getJsonObject("capabilities")
            .getJsonObject("tools"));
      });
  }

  @Test
  void ping_returns_empty_result(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 2).put("method", "ping"),
      response -> {
        JsonObject result = response.getJsonObject("result");
        assertNotNull(result);
        assertTrue(result.isEmpty());
      });
  }

  @Test
  void tools_list_returns_registered_tools(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 3).put("method", "tools/list"),
      response -> {
        JsonObject result = response.getJsonObject("result");
        assertNotNull(result);
        JsonArray toolList = result.getJsonArray("tools");
        assertNotNull(toolList);
        assertEquals(2, toolList.size());

        boolean hasPublish = false, hasTicket = false;
        for (int i = 0; i < toolList.size(); i++) {
          JsonObject t = toolList.getJsonObject(i);
          assertNotNull(t.getString("name"));
          assertNotNull(t.getString("description"));
          assertNotNull(t.getJsonObject("inputSchema"));
          if ("events.publish".equals(t.getString("name"))) hasPublish = true;
          if ("case.raiseTicket".equals(t.getString("name"))) hasTicket = true;
        }
        assertTrue(hasPublish, "Expected events.publish tool");
        assertTrue(hasTicket, "Expected case.raiseTicket tool");
      });
  }

  @Test
  void tools_call_invokes_raise_ticket(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 4)
        .put("method", "tools/call")
        .put("params", new JsonObject()
          .put("name", "case.raiseTicket")
          .put("arguments", new JsonObject()
            .put("tradeId", "T-MCP-1")
            .put("category", "ReferenceData")
            .put("summary", "MCP test ticket"))),
      response -> {
        assertNull(response.getJsonObject("error"),
            () -> "Unexpected error: " + response.getJsonObject("error"));
        JsonObject result = response.getJsonObject("result");
        JsonArray content = result.getJsonArray("content");
        assertEquals(1, content.size());
        assertEquals("text", content.getJsonObject(0).getString("type"));
        JsonObject toolResult = new JsonObject(
            content.getJsonObject(0).getString("text"));
        assertEquals("created", toolResult.getString("status"));
        assertTrue(toolResult.getString("ticketId").startsWith("TICKET-"));
        assertEquals("T-MCP-1", toolResult.getString("tradeId"));
      });
  }

  @Test
  void tools_call_publish_event(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 5)
        .put("method", "tools/call")
        .put("params", new JsonObject()
          .put("name", "events.publish")
          .put("arguments", new JsonObject()
            .put("type", "TestEvent")
            .put("tradeId", "T-MCP-2")
            .put("reason", "MCP test"))),
      response -> {
        assertNull(response.getJsonObject("error"),
            () -> "Unexpected error: " + response.getJsonObject("error"));
        JsonObject result = response.getJsonObject("result");
        JsonArray content = result.getJsonArray("content");
        assertEquals(1, content.size());
        JsonObject toolResult = new JsonObject(
            content.getJsonObject(0).getString("text"));
        assertEquals("published", toolResult.getString("status"));
      });
  }

  @Test
  void tools_call_unknown_tool_returns_error(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 6)
        .put("method", "tools/call")
        .put("params", new JsonObject()
          .put("name", "nonexistent.tool")
          .put("arguments", new JsonObject())),
      response -> {
        assertNotNull(response.getJsonObject("error"));
        assertEquals(-32602,
            response.getJsonObject("error").getInteger("code"));
        assertTrue(response.getJsonObject("error").getString("message")
            .contains("not allowlisted"));
      });
  }

  @Test
  void tools_call_missing_name_returns_error(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 7)
        .put("method", "tools/call")
        .put("params", new JsonObject()
          .put("arguments", new JsonObject())),
      response -> {
        assertNotNull(response.getJsonObject("error"));
        assertEquals(-32602,
            response.getJsonObject("error").getInteger("code"));
      });
  }

  @Test
  void unknown_method_returns_method_not_found(Vertx vertx, VertxTestContext ctx) {
    roundTrip(vertx, ctx, new JsonObject()
        .put("jsonrpc", "2.0").put("id", 8)
        .put("method", "nonexistent/method"),
      response -> {
        assertNotNull(response.getJsonObject("error"));
        assertEquals(-32601,
            response.getJsonObject("error").getInteger("code"));
      });
  }

  @Test
  void notification_returns_202(Vertx vertx, VertxTestContext ctx) {
    var verticle = new McpServerVerticle(tools, "tradeId");

    vertx.deployVerticle(verticle, portZero())
      .compose(id -> getSessionId(vertx, verticle.actualPort()))
      .compose(sessionId -> vertx.createHttpClient()
        .request(HttpMethod.POST, verticle.actualPort(), "localhost",
            "/message?sessionId=" + sessionId)
        .compose(req -> req
          .putHeader("content-type", "application/json")
          .send(Buffer.buffer(new JsonObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized").encode()))))
      .onSuccess(resp -> ctx.verify(() -> {
        assertEquals(202, resp.statusCode());
        ctx.completeNow();
      }))
      .onFailure(ctx::failNow);
  }

  // ── Helpers ───────────────────────────────────────────────────────

  /**
   * Deploy → open SSE → send JSON-RPC → receive response via SSE →
   * run assertions.
   */
  private void roundTrip(Vertx vertx, VertxTestContext ctx,
                         JsonObject jsonRpcRequest,
                         java.util.function.Consumer<JsonObject> assertions) {
    var verticle = new McpServerVerticle(tools, "tradeId");

    vertx.deployVerticle(verticle, portZero())
      .compose(id -> sseRoundTrip(vertx, verticle.actualPort(), jsonRpcRequest))
      .onSuccess(response -> ctx.verify(() -> {
        assertions.accept(response);
        ctx.completeNow();
      }))
      .onFailure(ctx::failNow);
  }

  /**
   * Opens an SSE connection, extracts the session ID, sends a JSON-RPC
   * POST, and resolves with the JSON-RPC response received via SSE.
   */
  private Future<JsonObject> sseRoundTrip(Vertx vertx, int port,
                                          JsonObject jsonRpcRequest) {
    Promise<JsonObject> promise = Promise.promise();

    vertx.createHttpClient()
      .request(HttpMethod.GET, port, "localhost", "/sse")
      .compose(HttpClientRequest::send)
      .onSuccess(sseResponse -> {
        AtomicReference<String> sessionRef = new AtomicReference<>();
        StringBuilder buf = new StringBuilder();

        sseResponse.handler(buffer -> {
          buf.append(buffer.toString());
          String text = buf.toString();

          if (sessionRef.get() == null) {
            // Extract session ID from endpoint event
            int idx = text.indexOf("data: /message?sessionId=");
            if (idx >= 0) {
              String after = text.substring(
                  idx + "data: /message?sessionId=".length());
              String sessionId = after.split("[\\s\\n]")[0].trim();
              sessionRef.set(sessionId);
              buf.setLength(0);

              // POST the JSON-RPC request
              vertx.createHttpClient()
                .request(HttpMethod.POST, port, "localhost",
                    "/message?sessionId=" + sessionId)
                .compose(req -> req
                  .putHeader("content-type", "application/json")
                  .send(Buffer.buffer(jsonRpcRequest.encode())))
                .onFailure(promise::fail);
            }
          } else {
            // Extract JSON-RPC response from message event
            int msgIdx = text.indexOf("event: message\ndata: ");
            if (msgIdx >= 0) {
              String json = text.substring(
                  msgIdx + "event: message\ndata: ".length()).trim();
              int end = json.indexOf("\n\n");
              if (end > 0) json = json.substring(0, end);
              try {
                promise.tryComplete(new JsonObject(json));
              } catch (Exception e) {
                promise.tryFail("Bad JSON-RPC response: " + json);
              }
            }
          }
        });
      })
      .onFailure(promise::fail);

    return promise.future();
  }

  /** Opens SSE and returns the session ID. */
  private Future<String> getSessionId(Vertx vertx, int port) {
    Promise<String> promise = Promise.promise();

    vertx.createHttpClient()
      .request(HttpMethod.GET, port, "localhost", "/sse")
      .compose(HttpClientRequest::send)
      .onSuccess(resp -> resp.handler(buffer -> {
        String data = buffer.toString();
        int idx = data.indexOf("sessionId=");
        if (idx >= 0) {
          String sessionId = data.substring(
              idx + "sessionId=".length()).split("[\\s\\n]")[0].trim();
          promise.tryComplete(sessionId);
        }
      }))
      .onFailure(promise::fail);

    return promise.future();
  }
}
