package dev.mars.agent.llm;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class OpenAiLlmClientTest {

  @Test
  void decide_next_returns_failed_future(Vertx vertx, VertxTestContext ctx) {
    var client = new OpenAiLlmClient(vertx,
        "https://api.example.com", "sk-test", "gpt-4");

    client.decideNext(new JsonObject(), new JsonObject())
      .onSuccess(r -> ctx.failNow("Expected failure since it's a placeholder"))
      .onFailure(err -> {
        assertTrue(err.getMessage().contains("placeholder"));
        ctx.completeNow();
      });
  }
}
