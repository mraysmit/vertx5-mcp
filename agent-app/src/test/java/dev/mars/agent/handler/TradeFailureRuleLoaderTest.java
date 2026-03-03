package dev.mars.agent.handler;
import dev.mars.mcp.tool.Tool;

import dev.mars.agent.llm.StubLlmClient;
import dev.mars.agent.llm.StubRuleSet;
import org.junit.jupiter.api.Test;
import io.vertx.core.json.JsonObject;

import static org.junit.jupiter.api.Assertions.*;

class TradeFailureRuleLoaderTest {

  @Test
  void load_returns_non_null_rule_set() {
    var loader = new TradeFailureRuleLoader();
    StubRuleSet ruleSet = loader.load();
    assertNotNull(ruleSet);
    assertNotNull(ruleSet.rules());
    assertNotNull(ruleSet.fallback());
  }

  @Test
  void lei_step0_triggers_lookup() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-1")
        .put("reason", "LEI not found");
    var state = new JsonObject().put("step", 0);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("data.lookup", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void lei_step1_triggers_classify() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-1")
        .put("reason", "LEI not found");
    var state = new JsonObject().put("step", 1);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("case.classify", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void lei_step2_triggers_raise_ticket() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-1")
        .put("reason", "LEI not found");
    var state = new JsonObject().put("step", 2);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("case.raiseTicket", result.getString("tool"));
    assertTrue(result.getBoolean("stop"));
    JsonObject args = result.getJsonObject("args");
    assertEquals("T-1", args.getString("tradeId"));
    assertNotNull(args.getString("detail"));
  }

  @Test
  void fallback_step0_triggers_lookup() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-2")
        .put("reason", "Unknown error XYZ");
    var state = new JsonObject().put("step", 0);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("data.lookup", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void fallback_step2_triggers_notify() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-2")
        .put("reason", "Unknown error XYZ");
    var state = new JsonObject().put("step", 2);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("comms.notify", result.getString("tool"));
    assertTrue(result.getBoolean("stop"));
  }

  // ── Settlement Amount Mismatch rule ───────────────────────────────

  @Test
  void mismatch_step0_triggers_lookup() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-500")
        .put("reason", "Settlement amount mismatch");
    var state = new JsonObject().put("step", 0);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("data.lookup", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
    assertNotNull(result.getString("reasoning"));
  }

  @Test
  void mismatch_step1_triggers_classify() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-500")
        .put("reason", "Settlement amount mismatch");
    var state = new JsonObject().put("step", 1);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("case.classify", result.getString("tool"));
    assertEquals("HIGH", result.getJsonObject("args").getString("severity"));
    assertEquals("Settlement", result.getJsonObject("args").getString("category"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void mismatch_step2_triggers_ticket() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-500")
        .put("reason", "Settlement amount mismatch");
    var state = new JsonObject().put("step", 2);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("case.raiseTicket", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void mismatch_step3_triggers_notify_and_stops() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-500")
        .put("reason", "Settlement amount mismatch");
    var state = new JsonObject().put("step", 3);

    var result = client.decideNext(event, state).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("comms.notify", result.getString("tool"));
    assertTrue(result.getBoolean("stop"));
    assertEquals("pagerduty", result.getJsonObject("args").getString("channel"));
  }

  // ── Duplicate Trade rule ──────────────────────────────────────────

  @Test
  void duplicate_step0_triggers_lookup() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-600")
        .put("reason", "Possible duplicate trade");
    var state = new JsonObject().put("step", 0);

    var result = client.decideNext(event, state).result();
    assertEquals("data.lookup", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void duplicate_step1_triggers_classify_critical() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-600")
        .put("reason", "Possible duplicate trade");
    var state = new JsonObject().put("step", 1);

    var result = client.decideNext(event, state).result();
    assertEquals("case.classify", result.getString("tool"));
    assertEquals("CRITICAL", result.getJsonObject("args").getString("severity"));
    assertEquals("Operations", result.getJsonObject("args").getString("category"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void duplicate_step3_triggers_pagerduty() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-600")
        .put("reason", "Possible duplicate trade");
    var state = new JsonObject().put("step", 3);

    var result = client.decideNext(event, state).result();
    assertEquals("comms.notify", result.getString("tool"));
    assertEquals("pagerduty", result.getJsonObject("args").getString("channel"));
    assertTrue(result.getBoolean("stop"));
  }

  // ── Regulatory Deadline rule ──────────────────────────────────────

  @Test
  void regulatory_step0_triggers_lookup() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-700")
        .put("reason", "Regulatory T+1 deadline at risk");
    var state = new JsonObject().put("step", 0);

    var result = client.decideNext(event, state).result();
    assertEquals("data.lookup", result.getString("tool"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void regulatory_step1_triggers_classify_compliance() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-700")
        .put("reason", "Regulatory T+1 deadline at risk");
    var state = new JsonObject().put("step", 1);

    var result = client.decideNext(event, state).result();
    assertEquals("case.classify", result.getString("tool"));
    assertEquals("CRITICAL", result.getJsonObject("args").getString("severity"));
    assertEquals("Compliance", result.getJsonObject("args").getString("category"));
    assertFalse(result.getBoolean("stop"));
  }

  @Test
  void regulatory_step3_triggers_pagerduty_to_compliance() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-700")
        .put("reason", "Regulatory T+1 deadline at risk");
    var state = new JsonObject().put("step", 3);

    var result = client.decideNext(event, state).result();
    assertEquals("comms.notify", result.getString("tool"));
    assertEquals("pagerduty", result.getJsonObject("args").getString("channel"));
    assertEquals("Compliance", result.getJsonObject("args").getString("team"));
    assertTrue(result.getBoolean("stop"));
  }
}
