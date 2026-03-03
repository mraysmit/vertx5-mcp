package dev.mars.agent.llm;

import io.vertx.core.json.JsonObject;

/**
 * A rule that the {@link StubLlmClient} evaluates against an incoming
 * event to decide which tool command to return.
 *
 * <p>Implementations inspect the event (e.g. keyword matching on the
 * {@code reason} field) and either return a fully-formed command
 * {@link JsonObject} or {@code null} to indicate no match, allowing the
 * next rule in the chain to be tried.
 *
 * <h2>Command schema</h2>
 * A matching rule must return a JSON object following the same contract
 * as {@link LlmClient#decideNext}:
 * <pre>
 * {
 *   "intent": "CALL_TOOL",
 *   "tool":   "tool.name",
 *   "args":   { ... },
 *   "stop":   true | false
 * }
 * </pre>
 *
 * @see StubLlmClient
 * @see StubLlmClient#keywordRule(String, String, java.util.function.Function)
 */
@FunctionalInterface
public interface StubRule {

  /**
   * Evaluate the event against this rule.
   *
   * @param event the incoming failure event
   * @return a command {@link JsonObject} if this rule matches, or
   *         {@code null} to fall through to the next rule
   */
  JsonObject tryMatch(JsonObject event);
}
