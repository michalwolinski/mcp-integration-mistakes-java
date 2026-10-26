package com.wolinski.mcpmistakes.server;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BiFunction;

/** Small helpers so each tool definition reads as name, description, schema, handler. */
final class Tools {

    static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    private Tools() {
    }

    static SyncToolSpecification tool(String name, String description, String inputSchema,
            BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder(name, JSON, inputSchema).description(description).build())
                .callHandler(handler)
                .build();
    }

    static CallToolResult text(String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
