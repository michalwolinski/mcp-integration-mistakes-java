package com.wolinski.mcpmistakes.server;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mistake 1. The code is identical in both variants; only the Logback configuration differs.
 * With a ConsoleAppender that has no {@code <target>}, this log line lands on stdout, which on
 * the stdio transport is the protocol channel.
 */
final class StockTools {

    private static final Logger log = LoggerFactory.getLogger(StockTools.class);

    private StockTools() {
    }

    static SyncToolSpecification reserveStock() {
        return Tools.tool("reserve_stock", "Reserve units of a SKU for an order.", """
                {
                  "type": "object",
                  "properties": {
                    "sku": { "type": "string", "pattern": "^SKU-[0-9]{5}$" },
                    "quantity": { "type": "integer", "minimum": 1, "maximum": 100 }
                  },
                  "required": ["sku", "quantity"],
                  "additionalProperties": false
                }
                """, (exchange, request) -> {
            String sku = (String) request.arguments().get("sku");
            int quantity = ((Number) request.arguments().get("quantity")).intValue();
            log.warn("{} is below its reorder point after reserving {}", sku, quantity);
            return Tools.text("Reserved %d x %s.".formatted(quantity, sku));
        });
    }
}
