package com.wolinski.mcpmistakes.server;

import com.wolinski.mcpmistakes.domain.Order;
import com.wolinski.mcpmistakes.domain.OrderBook;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.Optional;
import java.util.Set;

/**
 * Mistake 2: a business failure thrown as an exception instead of returned as a tool result.
 * The order book is read-only; a successful cancel only reports, which is all the demo needs.
 */
final class CancelOrderTools {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "order_id": { "type": "string", "pattern": "^ORD-[0-9]{6}$" }
              },
              "required": ["order_id"],
              "additionalProperties": false
            }
            """;

    private static final Set<String> CANCELLABLE = Set.of("placed", "paid");

    private CancelOrderTools() {
    }

    static SyncToolSpecification throwing(OrderBook book) {
        return Tools.tool("cancel_order", "Cancel an order.", SCHEMA, (exchange, request) -> {
            String id = (String) request.arguments().get("order_id");
            Order order = book.find(id).orElseThrow(() -> new IllegalArgumentException("Order not found"));
            if (false == CANCELLABLE.contains(order.status())) {
                throw new IllegalStateException("Illegal state transition");
            }
            return Tools.text("Cancelled %s.".formatted(id));
        });
    }

    static SyncToolSpecification reporting(OrderBook book) {
        return Tools.tool("cancel_order",
                "Cancel an order that has not shipped yet (status placed or paid).", SCHEMA, (exchange, request) -> {
            String id = (String) request.arguments().get("order_id");
            Optional<Order> found = book.find(id);
            if (found.isEmpty()) {
                return Tools.error("No order %s exists. Find the id with search_orders first.".formatted(id));
            }
            Order order = found.get();
            if (false == CANCELLABLE.contains(order.status())) {
                return Tools.error(("Order %s is %s, and only placed or paid orders can be cancelled. "
                        + "Tell the user it cannot be cancelled; do not retry.").formatted(id, order.status()));
            }
            return Tools.text("Cancelled %s.".formatted(id));
        });
    }
}
