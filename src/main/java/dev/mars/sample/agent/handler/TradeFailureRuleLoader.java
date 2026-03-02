package dev.mars.sample.agent.handler;

import dev.mars.sample.agent.llm.StubLlmClient;
import dev.mars.sample.agent.llm.StubRule;
import dev.mars.sample.agent.llm.StubRuleLoader;
import dev.mars.sample.agent.llm.StubRuleSet;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Rule loader for the <b>trade-failure</b> use case.
 *
 * <p>Assembles the stub rules that mimic how a real LLM would decide
 * which tool to invoke when the deterministic processor cannot handle a
 * failure:
 *
 * <ol>
 *   <li><b>LEI keyword rule</b> — if the failure reason contains
 *       {@code "lei"} (case-insensitive), raise a ticket categorised as
 *       {@code ReferenceData}.</li>
 *   <li><b>Fallback</b> — for any other unrecognised reason, publish a
 *       {@code TradeEscalated} event so a human can investigate.</li>
 * </ol>
 *
 * <p>To add a new trade-failure rule, add another entry to the
 * {@code rules} list returned by {@link #load()}.  To handle a
 * completely different domain (e.g. settlement failures), create a new
 * {@link StubRuleLoader} implementation instead.
 *
 * @see StubRuleLoader
 * @see StubRuleSet
 */
public class TradeFailureRuleLoader implements StubRuleLoader {

  private static final Logger LOG = Logger.getLogger(TradeFailureRuleLoader.class.getName());

  @Override
  public StubRuleSet load() {
    List<StubRule> rules = List.of(
      StubLlmClient.keywordRule("lei", "case.raiseTicket", event -> new JsonObject()
        .put("tradeId", event.getString("tradeId"))
        .put("category", "ReferenceData")
        .put("summary", "Counterparty LEI issue")
        .put("detail", "Failure reason: " + event.getString("reason")))
    );

    StubRule fallback = event -> new JsonObject()
      .put("intent", "CALL_TOOL")
      .put("tool", "events.publish")
      .put("args", new JsonObject()
        .put("type", "TradeEscalated")
        .put("tradeId", event.getString("tradeId"))
        .put("by", "agent")
        .put("reason", event.getString("reason")))
      .put("expected", "Escalation event published")
      .put("stop", true);

    LOG.info("Loaded trade-failure stub rules: " + rules.size() + " rules + fallback");
    return new StubRuleSet(rules, fallback);
  }
}
