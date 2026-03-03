package dev.mars.agent.config;

import dev.mars.agent.tool.PublishEventTool;
import dev.mars.agent.tool.RaiseTicketTool;
import dev.mars.mcp.tool.Tool;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class ToolFactoryTest {

  @Test
  void creates_publish_event_tool(Vertx vertx) {
    Tool tool = ToolFactory.create("publish-event", vertx, "events.out");
    assertInstanceOf(PublishEventTool.class, tool);
    assertEquals("events.publish", tool.name());
  }

  @Test
  void creates_raise_ticket_tool(Vertx vertx) {
    Tool tool = ToolFactory.create("raise-ticket", vertx, "events.out");
    assertInstanceOf(RaiseTicketTool.class, tool);
    assertEquals("case.raiseTicket", tool.name());
  }

  @Test
  void unknown_type_throws(Vertx vertx) {
    var ex = assertThrows(IllegalArgumentException.class,
        () -> ToolFactory.create("unknown-tool", vertx, "events.out"));
    assertTrue(ex.getMessage().contains("unknown-tool"));
  }
}
