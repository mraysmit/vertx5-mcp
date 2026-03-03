package dev.mars.agent.tool;

import dev.mars.mcp.tool.AgentContext;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class LookupToolTest {

  private AgentContext testCtx() {
    return new AgentContext("corr-1", "case-1", new JsonObject());
  }

  @Test
  void name_is_data_lookup() {
    var tool = new LookupTool();
    assertEquals("data.lookup", tool.name());
  }

  @Test
  void description_is_not_empty() {
    var tool = new LookupTool();
    assertFalse(tool.description().isBlank());
  }

  @Test
  void schema_declares_required_properties() {
    var tool = new LookupTool();
    var schema = tool.schema();
    assertEquals("object", schema.getString("type"));
    var props = schema.getJsonObject("properties");
    assertNotNull(props.getJsonObject("tradeId"));
    var required = schema.getJsonArray("required");
    assertTrue(required.contains("tradeId"));
  }

  @Test
  void invoke_returns_full_data_without_fields_filter() {
    var tool = new LookupTool();
    var args = new JsonObject().put("tradeId", "T-99");

    var result = tool.invoke(args, testCtx()).result();
    assertEquals("found", result.getString("status"));
    assertEquals("T-99", result.getString("tradeId"));
    var data = result.getJsonObject("data");
    assertNotNull(data);
    // should contain all sections: counterparty, security, settlement
    assertTrue(data.containsKey("counterparty"));
    assertTrue(data.containsKey("security"));
    assertTrue(data.containsKey("settlement"));
  }

  @Test
  void invoke_with_fields_filter_returns_subset() {
    var tool = new LookupTool();
    var args = new JsonObject()
        .put("tradeId", "T-99")
        .put("fields", new JsonArray().add("counterparty"));

    var result = tool.invoke(args, testCtx()).result();
    assertEquals("found", result.getString("status"));
    var data = result.getJsonObject("data");
    assertTrue(data.containsKey("counterparty"));
    assertFalse(data.containsKey("security"));
    assertFalse(data.containsKey("settlement"));
  }

  // ── Scenario-specific data tests ──────────────────────────────────

  @Test
  void invoke_t500_returns_settlement_mismatch_data() {
    var tool = new LookupTool();
    var args = new JsonObject().put("tradeId", "T-500");

    var result = tool.invoke(args, testCtx()).result();
    var data = result.getJsonObject("data");
    var settlement = data.getJsonObject("settlement");

    assertEquals("AMOUNT_MISMATCH", settlement.getString("status"));
    assertEquals(1_250_000, settlement.getInteger("expectedAmount"));
    assertEquals(1_500_000, settlement.getInteger("counterpartyAmount"));
    assertEquals(250_000, settlement.getInteger("discrepancy"));

    // FX data should be present
    var fx = data.getJsonObject("fx");
    assertNotNull(fx);
    assertEquals("EUR/USD", fx.getString("pair"));
    assertEquals(1.0842, fx.getDouble("ourRate"));
  }

  @Test
  void invoke_t600_returns_duplicate_trade_data() {
    var tool = new LookupTool();
    var args = new JsonObject().put("tradeId", "T-600");

    var result = tool.invoke(args, testCtx()).result();
    var data = result.getJsonObject("data");

    assertEquals("PENDING_REVIEW", data.getJsonObject("settlement").getString("status"));

    var related = data.getJsonArray("relatedTrades");
    assertNotNull(related);
    assertEquals(1, related.size());

    var rel = related.getJsonObject(0);
    assertEquals("T-599", rel.getString("tradeId"));
    assertEquals(0.99, rel.getDouble("matchScore"));
    assertEquals("3 seconds earlier", rel.getString("timeDelta"));
  }

  @Test
  void invoke_t700_returns_regulatory_deadline_data() {
    var tool = new LookupTool();
    var args = new JsonObject().put("tradeId", "T-700");

    var result = tool.invoke(args, testCtx()).result();
    var data = result.getJsonObject("data");
    var settlement = data.getJsonObject("settlement");

    assertEquals("2026-03-03", settlement.getString("valueDate"));
    assertEquals("SEC Rule 15c6-1 (T+1)", settlement.getString("regulatoryRule"));
    assertEquals("16:30 ET", settlement.getString("cutoffTime"));
    assertTrue(settlement.getBoolean("failReportRequired"));
  }

  @Test
  void invoke_t500_with_fields_filter_includes_fx() {
    var tool = new LookupTool();
    var args = new JsonObject()
        .put("tradeId", "T-500")
        .put("fields", new JsonArray().add("settlement").add("fx"));

    var result = tool.invoke(args, testCtx()).result();
    var data = result.getJsonObject("data");
    assertTrue(data.containsKey("settlement"));
    assertTrue(data.containsKey("fx"));
    assertFalse(data.containsKey("counterparty"));
    assertFalse(data.containsKey("security"));
  }

  @Test
  void invoke_default_trade_returns_base_data() {
    var tool = new LookupTool();
    var args = new JsonObject().put("tradeId", "T-99");

    var result = tool.invoke(args, testCtx()).result();
    var data = result.getJsonObject("data");

    // default data should NOT have scenario-specific fields
    assertFalse(data.containsKey("fx"));
    assertFalse(data.containsKey("relatedTrades"));
    assertEquals("UNMATCHED", data.getJsonObject("settlement").getString("status"));
    assertEquals("2026-03-05", data.getJsonObject("settlement").getString("valueDate"));
  }
}
