package com.wolinski.mcpmistakes.server;

import com.wolinski.mcpmistakes.domain.OrderBook;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One stdio MCP server, started by the client as a subprocess.
 *
 * <pre>
 * --variant=bad|good|endpoints   which tool set to register
 * --registration=list|map         list keeps declaration order; map goes through Map.of
 * --orders=N                      size of the order book
 * --export-seconds=S              how long one export takes
 * --export-failures=N            how many exports fail before one succeeds
 * --guard-stdout                  reserve the real stdout for the protocol (see guardedTransport)
 * </pre>
 */
public final class DemoServer {

    private DemoServer() {
    }

    public static void main(String[] argv) {
        Map<String, String> args = parse(argv);
        // The guard has to come first: anything that runs before it can still write to the real stdout.
        StdioServerTransportProvider transport = args.containsKey("guard-stdout")
                ? guardedTransport()
                : new StdioServerTransportProvider(Tools.JSON);

        OrderBook book = new OrderBook(Integer.parseInt(args.getOrDefault("orders", "200")));
        ExportTools exports = new ExportTools(
                Duration.ofSeconds(Long.parseLong(args.getOrDefault("export-seconds", "25"))),
                Integer.parseInt(args.getOrDefault("export-failures", "0")));

        List<SyncToolSpecification> tools = switch (args.getOrDefault("variant", "good")) {
            case "bad" -> List.of(
                    StockTools.reserveStock(),
                    CancelOrderTools.throwing(book),
                    OrderListingTools.unbounded(book),
                    exports.blocking(),
                    exports.count());
            case "endpoints" -> EndpointCatalog.tools();
            default -> "map".equals(args.get("registration"))
                    ? List.copyOf(Map.of(
                            "reserve_stock", StockTools.reserveStock(),
                            "cancel_order", CancelOrderTools.reporting(book),
                            "search_orders", OrderListingTools.paged(book),
                            "get_order", OrderListingTools.getOrder(book),
                            "start_export", exports.starting(),
                            "export_status", exports.status(),
                            "exports_written", exports.count()).values())
                    : List.of(
                            StockTools.reserveStock(),
                            CancelOrderTools.reporting(book),
                            OrderListingTools.paged(book),
                            OrderListingTools.getOrder(book),
                            exports.starting(),
                            exports.status(),
                            exports.count());
        };

        McpServer.sync(transport)
                .serverInfo("orders-demo", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
    }

    /**
     * Keeps the real stdout for the protocol and points System.out at stderr, so that nothing
     * else in the process (a stray println, a banner, a misconfigured appender) can reach the channel.
     */
    private static StdioServerTransportProvider guardedTransport() {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        return new StdioServerTransportProvider(Tools.JSON, System.in, protocol);
    }

    private static Map<String, String> parse(String[] argv) {
        Map<String, String> args = new HashMap<>();
        for (String arg : argv) {
            String[] pair = arg.replaceFirst("^--", "").split("=", 2);
            args.put(pair[0], 2 == pair.length ? pair[1] : "true");
        }
        return args;
    }
}
