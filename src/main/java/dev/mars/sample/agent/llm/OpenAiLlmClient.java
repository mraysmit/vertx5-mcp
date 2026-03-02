package dev.mars.sample.agent.llm;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.logging.Logger;

/**
 * Placeholder {@link LlmClient} implementation for calling a real LLM
 * (e.g.&nbsp;OpenAI, Azure OpenAI, Anthropic, or any compatible API).
 *
 * <h2>How to use</h2>
 * Replace the {@link StubLlmClient} in {@code MainVerticle} with an
 * instance of this class:
 * <pre>
 * // Before (stub):
 * new MainVerticle(memory, new TradeFailureRuleLoader().load().toClient());
 *
 * // After (real LLM):
 * new MainVerticle(memory, new OpenAiLlmClient(vertx, "https://api.openai.com/v1", apiKey, "gpt-4"));
 * </pre>
 *
 * <h2>Expected LLM response format</h2>
 * The LLM must return (or be prompted to return) a JSON object matching
 * the {@link LlmClient#decideNext} command schema:
 * <pre>
 * {
 *   "intent": "CALL_TOOL",
 *   "tool":   "case.raiseTicket",
 *   "args":   { "tradeId": "T-200", "category": "ReferenceData", ... },
 *   "stop":   true
 * }
 * </pre>
 *
 * <h2>Implementation notes</h2>
 * <ul>
 *   <li>Use {@link Vertx#createHttpClient()} or Vert.x
 *       {@code WebClient} to make non-blocking HTTP calls to the LLM
 *       API.</li>
 *   <li>Build a system prompt that describes the available tools (names,
 *       expected args) and instructs the LLM to respond with the JSON
 *       schema above.</li>
 *   <li>Include the {@code state} (conversation history) in the prompt
 *       so the LLM can make context-aware decisions across multiple
 *       steps.</li>
 *   <li>Parse the LLM's text response into a {@code JsonObject} and
 *       return it. Add error handling for malformed responses.</li>
 * </ul>
 *
 * @see LlmClient
 * @see StubLlmClient
 */
public class OpenAiLlmClient implements LlmClient {

  private static final Logger LOG = Logger.getLogger(OpenAiLlmClient.class.getName());

  private final Vertx vertx;
  private final String endpoint;
  private final String apiKey;
  private final String model;

  /**
   * Creates a new OpenAI LLM client.
   *
   * @param vertx    the Vert.x instance (for non-blocking HTTP calls)
   * @param endpoint the LLM API base URL
   *                 (e.g. {@code "https://api.openai.com/v1"})
   * @param apiKey   the API key for authentication
   * @param model    the model identifier (e.g. {@code "gpt-4"},
   *                 {@code "gpt-4o"})
   */
  public OpenAiLlmClient(Vertx vertx, String endpoint, String apiKey, String model) {
    this.vertx = vertx;
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.model = model;
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>TODO:</b> Implement the actual LLM call. The implementation
   * should:
   * <ol>
   *   <li>Build a prompt containing the event, prior state, and a list
   *       of available tools with their expected arguments.</li>
   *   <li>Send an HTTP POST to {@code endpoint + "/chat/completions"}
   *       with the model, system prompt, and user message.</li>
   *   <li>Parse the response and extract the JSON command object.</li>
   *   <li>Return the command as a {@code Future<JsonObject>}.</li>
   * </ol>
   */
  @Override
  public Future<JsonObject> decideNext(JsonObject event, JsonObject state) {
    LOG.info("OpenAiLlmClient.decideNext called — endpoint=" + endpoint + " model=" + model);
    LOG.fine("Event: " + event.encode());
    LOG.fine("State: " + state.encode());

    // ---------------------------------------------------------------
    // TODO: Replace this stub with a real HTTP call to the LLM API.
    //
    // Example flow (pseudocode):
    //
    //   String systemPrompt = buildSystemPrompt(availableTools);
    //   String userMessage  = "Event: " + event.encode()
    //                       + "\nState: " + state.encode();
    //
    //   JsonObject requestBody = new JsonObject()
    //     .put("model", model)
    //     .put("messages", new JsonArray()
    //       .add(new JsonObject().put("role", "system").put("content", systemPrompt))
    //       .add(new JsonObject().put("role", "user").put("content", userMessage)));
    //
    //   return webClient.postAbs(endpoint + "/chat/completions")
    //     .putHeader("Authorization", "Bearer " + apiKey)
    //     .sendJsonObject(requestBody)
    //     .map(response -> parseCommand(response.bodyAsJsonObject()));
    //
    // ---------------------------------------------------------------
    LOG.severe("OpenAiLlmClient is a placeholder — real LLM call not implemented");
    return Future.failedFuture(
      "OpenAiLlmClient is a placeholder — implement the HTTP call to " + endpoint);
  }
}
