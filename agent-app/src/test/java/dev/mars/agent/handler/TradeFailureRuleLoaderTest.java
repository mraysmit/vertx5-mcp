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
  void lei_keyword_triggers_raise_ticket() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-1")
        .put("reason", "LEI not found");

    var result = client.decideNext(event, new JsonObject()).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("case.raiseTicket", result.getString("tool"));
    assertTrue(result.getBoolean("stop"));
  }

  @Test
  void non_lei_reason_falls_back_to_escalation() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-2")
        .put("reason", "Unknown error XYZ");

    var result = client.decideNext(event, new JsonObject()).result();
    assertEquals("CALL_TOOL", result.getString("intent"));
    assertEquals("events.publish", result.getString("tool"));
    assertTrue(result.getBoolean("stop"));
  }

  @Test
  void lei_rule_includes_ticket_args() {
    var loader = new TradeFailureRuleLoader();
    StubLlmClient client = loader.load().toClient();

    var event = new JsonObject()
        .put("tradeId", "T-3")
        .put("reason", "Counterparty LEI mismatch");

    var result = client.decideNext(event, new JsonObject()).result();
    JsonObject args = result.getJsonObject("args");
    assertEquals("T-3", args.getString("tradeId"));
    assertEquals("ReferenceData", args.getString("category"));
    assertNotNull(args.getString("summary"));
    assertNotNull(args.getString("detail"));
  }
}
