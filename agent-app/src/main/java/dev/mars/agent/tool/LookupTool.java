package dev.mars.agent.tool;

import dev.mars.mcp.tool.AgentContext;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Agent tool that simulates looking up reference / enrichment data for a
 * trade failure — the kind of external-system query an LLM would instruct
 * as a first step before deciding what action to take.
 *
 * <p><b>Tool name:</b> {@code data.lookup}<br>
 * <b>Side effects:</b> none (read-only simulation).
 *
 * <h2>Why this matters</h2>
 * A real LLM doesn't just jump to an action. It gathers context first:
 * <em>"Let me look up the counterparty… ok, that LEI is expired — I should
 * raise a ReferenceData ticket."</em> This tool models the information-
 * gathering step that makes agentic reasoning valuable.
 *
 * <h2>Expected args</h2>
 * <pre>
 * {
 *   "tradeId":  "T-300",
 *   "fields":   ["counterparty", "security", "settlement"]   // optional
 * }
 * </pre>
 *
 * <h2>Return value</h2>
 * <pre>
 * {
 *   "status":   "found",
 *   "tradeId":  "T-300",
 *   "data": {
 *     "counterparty": { "name": "Acme Corp", "lei": "MISSING", "jurisdiction": "US" },
 *     "security":     { "isin": "US0378331005", "name": "Apple Inc", "currency": "USD" },
 *     "settlement":   { "csd": "DTCC", "status": "UNMATCHED", "valueDate": "2026-03-05" }
 *   }
 * }
 * </pre>
 */
public class LookupTool implements Tool {

  private static final Logger LOG = Logger.getLogger(LookupTool.class.getName());

  // ── Base reference data (used for all scenarios) ──────────────────
  private static final JsonObject DEFAULT_DATA = new JsonObject()
      .put("counterparty", new JsonObject()
          .put("name", "Acme Corp")
          .put("lei", "MISSING")
          .put("jurisdiction", "US")
          .put("rating", "BBB+"))
      .put("security", new JsonObject()
          .put("isin", "US0378331005")
          .put("name", "Apple Inc")
          .put("currency", "USD")
          .put("exchange", "NASDAQ"))
      .put("settlement", new JsonObject()
          .put("csd", "DTCC")
          .put("status", "UNMATCHED")
          .put("valueDate", "2026-03-05")
          .put("expectedAmount", 1_250_000));

  /**
   * Returns scenario-specific reference data based on the trade ID range.
   * This simulates what a real system would return from different data sources.
   */
  private static JsonObject dataForTrade(String tradeId) {
    JsonObject base = DEFAULT_DATA.copy();
    // deep-copy nested objects so mutations don't leak
    for (String key : DEFAULT_DATA.fieldNames()) {
      if (DEFAULT_DATA.getValue(key) instanceof JsonObject nested) {
        base.put(key, nested.copy());
      }
    }

    int num = parseTradeNum(tradeId);

    if (num >= 500 && num < 600) {
      // ── Settlement amount mismatch scenario ─────────────────────
      base.getJsonObject("settlement")
          .put("status", "AMOUNT_MISMATCH")
          .put("expectedAmount", 1_250_000)
          .put("counterpartyAmount", 1_500_000)
          .put("discrepancy", 250_000)
          .put("discrepancyPct", "20%");
      base.put("fx", new JsonObject()
          .put("pair", "EUR/USD")
          .put("ourRate", 1.0842)
          .put("marketRate", 1.0875)
          .put("rateMovement", "+0.30%")
          .put("rateDate", "2026-03-03")
          .put("note", "Rate moved 0.30% since trade booking — "
              + "counterparty may have applied T+1 rate"));

    } else if (num >= 600 && num < 700) {
      // ── Duplicate trade scenario ────────────────────────────────
      base.getJsonObject("settlement")
          .put("status", "PENDING_REVIEW")
          .put("bookedAt", "2026-03-03T14:32:01Z")
          .put("quantity", 10_000);
      base.put("relatedTrades", new JsonArray().add(new JsonObject()
          .put("tradeId", "T-" + (num - 1))
          .put("counterparty", "Acme Corp")
          .put("security", "AAPL")
          .put("quantity", 10_000)
          .put("direction", "BUY")
          .put("bookedAt", "2026-03-03T14:31:58Z")
          .put("timeDelta", "3 seconds earlier")
          .put("status", "SETTLED")
          .put("matchScore", 0.99)));

    } else if (num >= 700 && num < 800) {
      // ── Regulatory deadline scenario ────────────────────────────
      base.getJsonObject("settlement")
          .put("valueDate", "2026-03-03")   // today
          .put("regulatoryRule", "SEC Rule 15c6-1 (T+1)")
          .put("cutoffTime", "16:30 ET")
          .put("hoursRemaining", 3.5)
          .put("status", "UNMATCHED")
          .put("failReportRequired", true);
      base.getJsonObject("counterparty")
          .put("lei", "529900HNOAA1KXQJUH35")
          .put("jurisdiction", "US")
          .put("t1Mandated", true);
    }

    return base;
  }

  private static int parseTradeNum(String tradeId) {
    try {
      return Integer.parseInt(tradeId.replaceAll("[^0-9]", ""));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public String name() {
    return "data.lookup";
  }

  @Override
  public String description() {
    return "Looks up reference and enrichment data for a trade from external systems "
        + "(counterparty, security, settlement details).";
  }

  @Override
  public JsonObject schema() {
    return new JsonObject()
        .put("type", "object")
        .put("properties", new JsonObject()
            .put("tradeId", new JsonObject()
                .put("type", "string")
                .put("description", "The trade identifier to look up"))
            .put("fields", new JsonObject()
                .put("type", "array")
                .put("items", new JsonObject().put("type", "string"))
                .put("description", "Specific data fields to retrieve (default: all)")))
        .put("required", new JsonArray().add("tradeId"));
  }

  @Override
  public Future<JsonObject> invoke(JsonObject args, AgentContext ctx) {
    String tradeId = args.getString("tradeId", ctx.caseId());
    LOG.info("Looking up data for tradeId=" + tradeId + " caseId=" + ctx.caseId());

    // Load scenario-appropriate data based on trade ID range
    JsonObject fullData = dataForTrade(tradeId);

    // Simulate field filtering
    JsonArray fields = args.getJsonArray("fields");
    JsonObject data;
    if (fields != null && !fields.isEmpty()) {
      data = new JsonObject();
      for (int i = 0; i < fields.size(); i++) {
        String field = fields.getString(i);
        if (fullData.containsKey(field)) {
          Object val = fullData.getValue(field);
          data.put(field, val instanceof JsonObject jo ? jo.copy()
              : val instanceof JsonArray ja ? ja.copy() : val);
        }
      }
    } else {
      data = fullData;
    }

    JsonObject result = new JsonObject()
        .put("status", "found")
        .put("tradeId", tradeId)
        .put("data", data);

    LOG.info("Lookup completed for tradeId=" + tradeId + ": " + data.fieldNames().size() + " sections returned");
    return Future.succeededFuture(result);
  }
}
