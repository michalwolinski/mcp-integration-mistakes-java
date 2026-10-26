package com.wolinski.mcpmistakes.server;

import com.wolinski.mcpmistakes.domain.Order;
import com.wolinski.mcpmistakes.domain.OrderBook;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mistake 4: a tool result whose size is set by the data, not by the tool. */
final class OrderListingTools {

    static final int MAX_LIMIT = 50;

    private OrderListingTools() {
    }

    static SyncToolSpecification unbounded(OrderBook book) {
        return Tools.tool("list_orders", "List all orders.", """
                { "type": "object", "additionalProperties": false }
                """, (exchange, request) -> Tools.text(Tools.json(book.all())));
    }

    static SyncToolSpecification paged(OrderBook book) {
        Tool tool = Tool.builder("search_orders", Tools.JSON, """
                {
                  "type": "object",
                  "properties": {
                    "status": { "type": "string", "enum": ["placed", "paid", "shipped", "delivered", "cancelled"] },
                    "limit": { "type": "integer", "minimum": 1, "maximum": 50, "default": 20 },
                    "cursor": { "type": "string", "description": "next_cursor from the previous page" }
                  },
                  "additionalProperties": false
                }
                """)
                .description("Find orders, newest id last. Returns at most 50 summaries per call; "
                        + "pass next_cursor back to read the next page. Use get_order for line items.")
                .outputSchema(Tools.JSON, """
                        {
                          "type": "object",
                          "properties": {
                            "orders": {
                              "type": "array",
                              "items": {
                                "type": "object",
                                "properties": {
                                  "id": { "type": "string" },
                                  "customer": { "type": "string" },
                                  "status": { "type": "string" },
                                  "placed_at": { "type": "string" },
                                  "total": { "type": "string" },
                                  "lines": { "type": "integer" }
                                },
                                "required": ["id", "customer", "status", "placed_at", "total", "lines"]
                              }
                            },
                            "next_cursor": { "type": ["string", "null"] }
                          },
                          "required": ["orders", "next_cursor"]
                        }
                        """)
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            Map<String, Object> args = request.arguments();
            String status = (String) args.get("status");
            String cursor = (String) args.get("cursor");
            int limit = Math.min(args.get("limit") instanceof Number n ? n.intValue() : 20, MAX_LIMIT);
            List<Order> page = book.page(status, cursor, limit + 1);
            boolean more = page.size() > limit;
            List<Order> shown = more ? page.subList(0, limit) : page;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orders", shown.stream().map(OrderListingTools::summary).toList());
            result.put("next_cursor", more ? shown.getLast().id() : null);
            return CallToolResult.builder()
                    .addTextContent(Tools.json(result))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        }).build();
    }

    static SyncToolSpecification getOrder(OrderBook book) {
        return Tools.tool("get_order", "Get one order with its line items.", """
                {
                  "type": "object",
                  "properties": {
                    "order_id": { "type": "string", "pattern": "^ORD-[0-9]{6}$" }
                  },
                  "required": ["order_id"],
                  "additionalProperties": false
                }
                """, (exchange, request) -> {
            String id = (String) request.arguments().get("order_id");
            return book.find(id)
                    .map(order -> Tools.text(Tools.json(order)))
                    .orElseGet(() -> Tools.error("No order %s exists. Find the id with search_orders first.".formatted(id)));
        });
    }

    private static Map<String, Object> summary(Order order) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", order.id());
        summary.put("customer", order.customer());
        summary.put("status", order.status());
        summary.put("placed_at", order.placedAt());
        summary.put("total", order.total());
        summary.put("lines", order.lines().size());
        return summary;
    }
}
