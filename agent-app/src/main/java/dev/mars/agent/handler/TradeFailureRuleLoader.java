package dev.mars.agent.handler;
import dev.mars.mcp.tool.Tool;

import dev.mars.agent.llm.StubLlmClient;
import dev.mars.agent.llm.StubRule;
import dev.mars.agent.llm.StubRuleLoader;
import dev.mars.agent.llm.StubRuleSet;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Rule loader for the <b>trade-failure</b> use case.
 *
 * <p>Assembles multi-step stub rules that simulate how a real LLM would
 * reason through a trade failure — gathering data, classifying the issue,
 * and then taking the appropriate action.
 *
 * <h2>LEI keyword rule (3 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — gather counterparty + settlement data.</li>
 *   <li><b>Classify</b> — ReferenceData / HIGH.</li>
 *   <li><b>Raise ticket</b> — for the Reference Data team.</li>
 * </ol>
 *
 * <h2>Settlement Amount Mismatch rule (4 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — settlement, counterparty + FX data.</li>
 *   <li><b>Classify</b> — Settlement / HIGH with FX analysis.</li>
 *   <li><b>Raise ticket</b> — root-cause hypothesis (FX timing).</li>
 *   <li><b>Notify</b> — PagerDuty to Reconciliation + Risk.</li>
 * </ol>
 *
 * <h2>Duplicate Trade rule (4 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — full trade data + related trades.</li>
 *   <li><b>Classify</b> — Operations / CRITICAL with cross-reference.</li>
 *   <li><b>Raise ticket</b> — P1 with duplicate evidence.</li>
 *   <li><b>Notify</b> — PagerDuty to Trade Operations.</li>
 * </ol>
 *
 * <h2>Regulatory Deadline rule (4 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — settlement timeline + counterparty.</li>
 *   <li><b>Classify</b> — Compliance / CRITICAL with SEC analysis.</li>
 *   <li><b>Raise ticket</b> — with full regulatory context.</li>
 *   <li><b>Notify</b> — PagerDuty to Compliance.</li>
 * </ol>
 *
 * <h2>Fallback (3 steps)</h2>
 * <ol>
 *   <li><b>Lookup</b> — gather all trade context.</li>
 *   <li><b>Classify</b> — Operations / MEDIUM.</li>
 *   <li><b>Notify</b> — Slack to Operations team.</li>
 * </ol>
 *
 * <p>Each rule uses {@code stop: false} to continue the agent loop
 * until the final step sets {@code stop: true}. Rules inspect
 * {@code state.getInteger("step")} to determine which step of the
 * reasoning chain to execute.
 *
 * @see StubRuleLoader
 * @see StubRuleSet
 */
public class TradeFailureRuleLoader implements StubRuleLoader {

  private static final Logger LOG = Logger.getLogger(TradeFailureRuleLoader.class.getName());

  @Override
  public StubRuleSet load() {

    // ── LEI keyword rule — 3-step chain ──────────────────────────────
    StubRule leiRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        // single-arg version not used when state is available
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("lei")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Gather data — "Let me look up the counterparty details"
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("counterparty").add("settlement")))
              .put("reasoning", "I need to look up the counterparty data to understand the LEI issue")
              .put("stop", false);

          // Step 1: Classify — "The LEI is missing, this is a reference data issue"
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "ReferenceData")
                  .put("severity", "HIGH")
                  .put("reason", "Counterparty LEI missing — jurisdiction US, "
                      + "rated BBB+, high-value trade requires valid LEI"))
              .put("reasoning", "Lookup shows LEI is MISSING for a US-jurisdiction "
                  + "counterparty — this is a HIGH severity reference data issue")
              .put("stop", false);

          // Step 2: Act — "I'll raise a ticket for the Reference Data team"
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "ReferenceData")
                  .put("summary", "Counterparty LEI missing — HIGH severity")
                  .put("detail", "Failure reason: " + event.getString("reason")
                      + ". Lookup shows counterparty Acme Corp (US, BBB+) "
                      + "has no LEI on file. Trade is UNMATCHED at DTCC. "
                      + "Classified as ReferenceData/HIGH with 0.92 confidence."))
              .put("reasoning", "Classification confirmed HIGH severity — raising "
                  + "a ReferenceData ticket with full context from lookup")
              .put("stop", true);
        };
      }
    };

    // ── Settlement Amount Mismatch — 4-step chain ─────────────────────
    StubRule mismatchRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("amount") && !reason.contains("mismatch")
            && !reason.contains("settlement mismatch")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Gather settlement + counterparty + FX data
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("settlement").add("counterparty").add("fx")))
              .put("reasoning", "Settlement amount mismatch reported — I need to pull "
                  + "the full settlement record including both our expected amount and "
                  + "the counterparty's submitted amount, plus any FX rate data that "
                  + "could explain a conversion discrepancy.")
              .put("stop", false);

          // Step 1: Classify with FX analysis
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Settlement")
                  .put("severity", "HIGH")
                  .put("reason", "Amount discrepancy of $250,000 (20%). "
                      + "Our expected: $1,250,000 vs counterparty submitted: $1,500,000. "
                      + "FX analysis: EUR/USD moved +0.30% since booking — counterparty "
                      + "may have applied T+1 rate (1.0875) vs our booked rate (1.0842). "
                      + "Correlation suggests FX timing issue, but absolute amount "
                      + "exceeds $10K tolerance."))
              .put("reasoning", "Lookup reveals our expected amount is $1,250,000 but the "
                  + "counterparty submitted $1,500,000 — a $250,000 discrepancy (20%). "
                  + "I notice the EUR/USD rate moved +0.30% since booking and the "
                  + "counterparty is EU-based. This correlation strongly suggests an FX "
                  + "conversion timing issue rather than a genuine trade break. However, "
                  + "the absolute amount ($250K) far exceeds the $10K tolerance threshold, "
                  + "so this must be classified as Settlement / HIGH regardless of root cause.")
              .put("stop", false);

          // Step 2: Raise ticket with root-cause hypothesis
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Settlement")
                  .put("summary", "Amount mismatch $250K (20%) — likely FX timing issue")
                  .put("detail", "Expected: $1,250,000 | Counterparty: $1,500,000 | "
                      + "Discrepancy: $250,000 (20%).\n\n"
                      + "Root-cause hypothesis: Counterparty (Acme Corp, EU jurisdiction) "
                      + "appears to have applied the T+1 EUR/USD rate (1.0875) vs our "
                      + "booked rate (1.0842). Rate moved +0.30% between booking and "
                      + "settlement instruction.\n\n"
                      + "Action required: Reconciliation desk to verify which FX rate "
                      + "was contractually agreed and whether re-booking at the correct "
                      + "rate resolves the break."))
              .put("reasoning", "The magnitude of this discrepancy ($250K) and its likely "
                  + "root cause (FX rate timing mismatch) require the Reconciliation desk "
                  + "to verify which rate was contractually agreed. I'm creating a HIGH "
                  + "ticket with the FX hypothesis documented so the team can investigate "
                  + "efficiently rather than starting from scratch.")
              .put("stop", false);

          // Step 3: Notify both Reconciliation and Risk
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Reconciliation + Risk")
                  .put("subject", "HIGH: $250K settlement mismatch on " + tradeId
                      + " — FX timing issue suspected")
                  .put("body", "Trade " + tradeId + " has a $250,000 (20%) settlement "
                      + "amount mismatch.\n\n"
                      + "Likely cause: FX conversion timing (EUR/USD +0.30% since booking).\n"
                      + "Value date approaching — immediate attention required.\n\n"
                      + "Ticket raised for Reconciliation. Risk team copied due to "
                      + "material exposure."))
              .put("reasoning", "Given the material amount ($250K) and the approaching value "
                  + "date, I'm alerting both Reconciliation (to fix the break) and Risk "
                  + "(to assess exposure) via PagerDuty. Standard Slack notification isn't "
                  + "appropriate here — waiting for the next business day would increase "
                  + "the risk of a failed settlement.")
              .put("stop", true);
        };
      }
    };

    // ── Duplicate Trade — 4-step chain ──────────────────────────────
    StubRule duplicateRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("duplicate")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Pull full trade data + related trades
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject().put("tradeId", tradeId))
              .put("reasoning", "Duplicate trade flagged — I need to examine the full trade "
                  + "details and check for similar recent bookings by the same counterparty. "
                  + "The lookup should reveal any related trades booked in a close time window.")
              .put("stop", false);

          // Step 1: Classify as CRITICAL with cross-reference reasoning
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Operations")
                  .put("severity", "CRITICAL")
                  .put("reason", "High-confidence duplicate detected (match score 0.99). "
                      + "Trade " + tradeId + " booked at 14:32:01 is near-identical to "
                      + "a trade booked 3 seconds earlier — same counterparty (Acme Corp), "
                      + "same security (AAPL), same quantity (10,000 shares), same direction "
                      + "(BUY). Pattern consistent with retry-after-timeout double execution. "
                      + "Unintended market exposure: ~$1.25M."))
              .put("reasoning", "Analysis confirms high duplicate probability: Trade " + tradeId
                  + " for Acme Corp — 10,000 shares AAPL at NASDAQ — was booked at "
                  + "14:32:01. I found a prior trade with IDENTICAL parameters booked at "
                  + "14:31:58, just 3 seconds earlier, with a 0.99 match score. The execution "
                  + "venue, quantity, counterparty, and direction all match exactly. This "
                  + "pattern is consistent with a retry-after-timeout causing double execution. "
                  + "Classifying as Operations / CRITICAL because duplicate execution means "
                  + "unintended market exposure of approximately $1.25M.")
              .put("stop", false);

          // Step 2: Raise urgent P1 ticket
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Operations")
                  .put("summary", "CRITICAL: Probable duplicate execution — $1.25M unintended exposure")
                  .put("detail", "Two near-identical trades detected:\n\n"
                      + "  Trade " + tradeId + " booked 14:32:01 → PENDING_REVIEW\n"
                      + "  Related trade booked 14:31:58 → SETTLED (match score 0.99)\n\n"
                      + "Both: Acme Corp, 10,000 shares AAPL BUY @ NASDAQ.\n"
                      + "Time delta: 3 seconds — consistent with retry-after-timeout.\n\n"
                      + "URGENT: Verify with execution venue whether both fills are genuine. "
                      + "If confirmed duplicate, cancel " + tradeId + " immediately to "
                      + "eliminate ~$1.25M unintended exposure."))
              .put("reasoning", "CRITICAL situation requires an urgent P1 ticket for Trade "
                  + "Operations. I'm documenting the key evidence — two identical fills "
                  + "3 seconds apart, same counterparty, same security, same quantity — so "
                  + "the team can immediately verify with the execution venue whether both "
                  + "fills are genuine or if " + tradeId + " should be cancelled.")
              .put("stop", false);

          // Step 3: PagerDuty alert (Slack too slow for CRITICAL)
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Trade Operations")
                  .put("subject", "CRITICAL: Duplicate execution — " + tradeId
                      + " / ~$1.25M exposure")
                  .put("body", "DUPLICATE EXECUTION ALERT\n\n"
                      + "Trade " + tradeId + " appears to be a duplicate of a trade "
                      + "booked 3 seconds earlier (match score 0.99).\n\n"
                      + "Unintended exposure: ~$1.25M on AAPL.\n"
                      + "Pattern: retry-after-timeout — verify with venue immediately.\n\n"
                      + "Ticket created. This is a P1 — requires immediate response."))
              .put("reasoning", "Duplicate execution is a CRITICAL risk scenario with ~$1.25M "
                  + "of unintended AAPL exposure. Standard Slack notification is NOT "
                  + "sufficient — every minute of delay increases risk if the market moves "
                  + "against the duplicate position. Escalating to PagerDuty to ensure the "
                  + "Trade Operations on-call team is alerted immediately for manual "
                  + "intervention.")
              .put("stop", true);
        };
      }
    };

    // ── Regulatory Deadline at Risk — 4-step chain ──────────────────
    StubRule regulatoryRule = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        String reason = event.getString("reason", "").toLowerCase();
        if (!reason.contains("regulatory") && !reason.contains("deadline")
            && !reason.contains("compliance") && !reason.contains("t+1")) return null;

        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");

        return switch (step) {
          // Step 0: Check settlement timeline
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("fields", new io.vertx.core.json.JsonArray()
                      .add("settlement").add("counterparty")))
              .put("reasoning", "Compliance-flagged failure — I need to check the settlement "
                  + "timeline and regulatory context immediately. If this trade has a same-day "
                  + "value date under T+1 rules, the deadline may be imminent.")
              .put("stop", false);

          // Step 1: Classify as CRITICAL/Compliance with regulatory analysis
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Compliance")
                  .put("severity", "CRITICAL")
                  .put("reason", "SEC Rule 15c6-1 (T+1) deadline at risk. "
                      + "Value date is TODAY (2026-03-03), trade status UNMATCHED at DTCC. "
                      + "Estimated 3.5 hours until DTCC cutoff at 16:30 ET. "
                      + "Counterparty is US-jurisdiction, T+1 mandated. "
                      + "Failure to settle will trigger mandatory SEC fail report."))
              .put("reasoning", "CRITICAL finding: Settlement value date is 2026-03-03 "
                  + "(TODAY) and the trade is still in UNMATCHED status at DTCC. Under SEC "
                  + "Rule 15c6-1, this trade MUST settle by end of day. Current time analysis: "
                  + "approximately 3.5 hours remain until DTCC's final settlement cutoff at "
                  + "16:30 ET. The counterparty (Acme Corp, US jurisdiction, LEI confirmed) is "
                  + "subject to the same T+1 requirements. Failure to settle will trigger a "
                  + "mandatory fail report to the SEC — this is not just an operational issue, "
                  + "it is a potential regulatory breach. Classifying as Compliance / CRITICAL.")
              .put("stop", false);

          // Step 2: Raise ticket with full regulatory context
          case 2 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.raiseTicket")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Compliance")
                  .put("summary", "CRITICAL: SEC T+1 deadline breach imminent — " + tradeId)
                  .put("detail", "Trade " + tradeId + " is at risk of breaching SEC Rule "
                      + "15c6-1 (T+1 settlement mandate).\n\n"
                      + "Value date: 2026-03-03 (TODAY)\n"
                      + "Current status: UNMATCHED at DTCC\n"
                      + "DTCC cutoff: 16:30 ET (~3.5 hours remaining)\n"
                      + "Counterparty: Acme Corp (US, T+1 mandated)\n\n"
                      + "If not resolved before cutoff, a mandatory fail report must be "
                      + "filed with the SEC. The Compliance team should assess whether "
                      + "pre-emptive notification to the regulator is advisable.\n\n"
                      + "IMMEDIATE ACTION: Contact DTCC and counterparty to resolve "
                      + "matching discrepancy before 16:30 ET."))
              .put("reasoning", "Creating a CRITICAL compliance ticket with the full regulatory "
                  + "context. The SEC fail-report deadline creates a hard constraint — this "
                  + "isn't a soft SLA, it's a legal obligation. I'm documenting the specific "
                  + "timeline (3.5h remaining), the applicable rule (15c6-1), and the "
                  + "consequence (mandatory SEC fail report) so the team understands the "
                  + "urgency without needing to research it themselves.")
              .put("stop", false);

          // Step 3: PagerDuty to Compliance
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "pagerduty")
                  .put("team", "Compliance")
                  .put("subject", "CRITICAL: SEC T+1 breach imminent — " + tradeId
                      + " UNMATCHED, cutoff 16:30 ET")
                  .put("body", "REGULATORY DEADLINE ALERT\n\n"
                      + "Trade " + tradeId + " will breach SEC Rule 15c6-1 (T+1) if not "
                      + "settled by 16:30 ET today.\n\n"
                      + "Status: UNMATCHED at DTCC\n"
                      + "Time remaining: ~3.5 hours\n"
                      + "Consequence: Mandatory SEC fail report\n\n"
                      + "Compliance ticket created. Immediate action required to contact "
                      + "DTCC and counterparty for matching resolution."))
              .put("reasoning", "Regulatory deadline breach is imminent — notifying Compliance "
                  + "via PagerDuty, not Slack. The SEC T+1 mandate means this cannot wait "
                  + "for the next standup or business day. I'm including specific actionable "
                  + "context: trade ID, current status (UNMATCHED), regulatory rule (15c6-1), "
                  + "and estimated time remaining (~3.5h) before the DTCC cutoff so the "
                  + "on-call Compliance officer can act immediately.")
              .put("stop", true);
        };
      }
    };

    // ── Fallback rule — 3-step chain for any unrecognised reason ──────
    StubRule fallback = new StubRule() {
      @Override
      public JsonObject tryMatch(JsonObject event) {
        return tryMatch(event, new JsonObject().put("step", 0));
      }

      @Override
      public JsonObject tryMatch(JsonObject event, JsonObject state) {
        int step = state != null ? state.getInteger("step", 0) : 0;
        String tradeId = event.getString("tradeId", "unknown");
        String reason = event.getString("reason", "Unknown failure");

        return switch (step) {
          // Step 0: Gather data — "Let me look up all available information"
          case 0 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "data.lookup")
              .put("args", new JsonObject().put("tradeId", tradeId))
              .put("reasoning", "Unrecognised failure — gathering all available "
                  + "trade data before deciding on an action")
              .put("stop", false);

          // Step 1: Classify — "Based on what I found, this looks like..."
          case 1 -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "case.classify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("category", "Operations")
                  .put("severity", "MEDIUM")
                  .put("reason", "Unrecognised failure pattern: " + reason
                      + " — trade data shows UNMATCHED status, "
                      + "requires operational investigation"))
              .put("reasoning", "Trade data retrieved — failure doesn't match "
                  + "known patterns, classifying as Operations/MEDIUM for investigation")
              .put("stop", false);

          // Step 2: Notify — "I'll alert the Operations team"
          default -> new JsonObject()
              .put("intent", "CALL_TOOL")
              .put("tool", "comms.notify")
              .put("args", new JsonObject()
                  .put("tradeId", tradeId)
                  .put("channel", "slack")
                  .put("team", "Operations")
                  .put("subject", "MEDIUM: Investigation needed for trade " + tradeId)
                  .put("body", "Trade " + tradeId + " failed with reason: " + reason
                      + ".\n\nClassified as Operations/MEDIUM.\n"
                      + "Settlement status: UNMATCHED at DTCC.\n"
                      + "Please investigate and resolve."))
              .put("reasoning", "Classified as Operations/MEDIUM — notifying the "
                  + "Operations team via Slack with full context")
              .put("stop", true);
        };
      }
    };

    LOG.info("Loaded trade-failure stub rules: 4 multi-step rules + multi-step fallback");
    return new StubRuleSet(List.of(leiRule, mismatchRule, duplicateRule, regulatoryRule), fallback);
  }
}
