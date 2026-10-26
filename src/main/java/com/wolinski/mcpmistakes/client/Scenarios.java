package com.wolinski.mcpmistakes.client;

import com.wolinski.mcpmistakes.domain.Order;
import com.wolinski.mcpmistakes.domain.OrderBook;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * The MCP client side of the demo. Each scenario launches its own server subprocess over stdio
 * and prints what the client actually received. Output of a full run is committed as scenarios.log.
 *
 * <p>Every step also carries the outcome the article claims for it. The transcript shows what happened;
 * the closing "== expectations" line says whether that still matches the article, and the exit code is
 * non-zero when it does not, so a dependency upgrade that changes the behaviour cannot pass unnoticed.
 */
public final class Scenarios {

    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();

    private static final String TIMEOUT = "TimeoutException";

    private static final List<String> unmet = new ArrayList<>();

    private static int met;

    private Scenarios() {
    }

    public static void main(String[] args) {
        Set<String> only = Set.of(args);
        try {
            if (only.isEmpty() || only.contains("1")) {
                stdoutLogging();
            }
            if (only.isEmpty() || only.contains("2")) {
                exceptionsVersusResults();
            }
            if (only.isEmpty() || only.contains("3")) {
                catalogAsPrompt();
            }
            if (only.isEmpty() || only.contains("4")) {
                unboundedResults();
            }
            if (only.isEmpty() || only.contains("5")) {
                requestsThatOutliveTheClient();
            }
        }
        catch (Exception e) {
            unmet.add("runner aborted: " + e);
        }
        System.out.printf("%n== expectations: %d met, %d unmet%n", met, unmet.size());
        unmet.forEach(line -> System.out.println("  UNMET " + line));
        // A failed scenario can leave a server subprocess behind; do not let it keep the JVM alive.
        System.exit(unmet.isEmpty() ? 0 : 1);
    }

    // Mistake 1 ------------------------------------------------------------------------------

    private record LoggingRun(List<String> launch, List<String> expected) {
    }

    private static void stdoutLogging() {
        heading("1. Logging to stdout on the stdio transport");
        String failedInit = "Client failed to initialize";
        List<String> healthy = List.of("protocol 2025-11-25", "7 tools", "isError=false", "7 tools");
        List<LoggingRun> runs = List.of(
                new LoggingRun(List.of("logback-stdout.xml", "--variant=good"),
                        List.of(failedInit, failedInit, failedInit, failedInit)),
                new LoggingRun(List.of("logback-stdout-warn.xml", "--variant=good"),
                        List.of("protocol 2025-11-25", "7 tools", TIMEOUT, TIMEOUT)),
                new LoggingRun(List.of("logback-stderr.xml", "--variant=good"), healthy),
                new LoggingRun(List.of("logback-stdout.xml", "--variant=good", "--guard-stdout"), healthy));
        for (LoggingRun run : runs) {
            System.out.printf("[%s]%n", String.join(" ", run.launch()));
            List<String> launch = run.launch();
            try (McpSyncClient client = connect(launch.getFirst(), launch.subList(1, launch.size()).toArray(String[]::new))) {
                step("initialize", run.expected().get(0), () -> "protocol " + client.initialize().protocolVersion());
                step("tools/list", run.expected().get(1), () -> client.listTools().tools().size() + " tools");
                step("reserve_stock", run.expected().get(2),
                        () -> describe(call(client, "reserve_stock", Map.of("sku", "SKU-00042", "quantity", 3))));
                step("tools/list again", run.expected().get(3), () -> client.listTools().tools().size() + " tools");
            }
        }
    }

    // Mistake 2 ------------------------------------------------------------------------------

    private static void exceptionsVersusResults() {
        heading("2. Throwing from a tool instead of returning a tool error");
        String shipped = new OrderBook(200).all().stream()
                .filter(order -> "shipped".equals(order.status()))
                .map(Order::id)
                .findFirst()
                .orElseThrow();
        String schemaError = "isError=true, text=\"Tool (cancel_order) input validation failed";
        System.out.println("[bad]");
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=bad")) {
            step("cancel_order " + shipped, "JSON-RPC error -32603: Illegal state transition",
                    () -> describe(call(client, "cancel_order", Map.of("order_id", shipped))));
            step("cancel_order ORD-999999", "JSON-RPC error -32603: Order not found",
                    () -> describe(call(client, "cancel_order", Map.of("order_id", "ORD-999999"))));
            step("cancel_order ORD-42 (schema)", schemaError,
                    () -> describe(call(client, "cancel_order", Map.of("order_id", "ORD-42"))));
        }
        System.out.println("[good]");
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=good")) {
            step("cancel_order " + shipped, "isError=true, text=\"Order %s is shipped".formatted(shipped),
                    () -> describe(call(client, "cancel_order", Map.of("order_id", shipped))));
            step("cancel_order ORD-999999", "isError=true, text=\"No order ORD-999999 exists",
                    () -> describe(call(client, "cancel_order", Map.of("order_id", "ORD-999999"))));
            step("cancel_order ORD-42 (schema)", schemaError,
                    () -> describe(call(client, "cancel_order", Map.of("order_id", "ORD-42"))));
        }
    }

    // Mistake 3 ------------------------------------------------------------------------------

    private static void catalogAsPrompt() {
        heading("3. Treating the tool catalog as an API instead of a prompt");
        Map<String, Integer> expectedTools = Map.of("endpoints", 35, "good", 7);
        Map<String, Integer> bytes = new HashMap<>();
        for (String variant : List.of("endpoints", "good")) {
            try (McpSyncClient client = open("logback-stderr.xml", "--variant=" + variant)) {
                ListToolsResult tools = client.listTools();
                int size = utf8(writeJson(tools));
                bytes.put(variant, size);
                System.out.printf("  %-10s %2d tools, tools/list result = %,d bytes%n", variant, tools.tools().size(), size);
                expect(variant + " tool count", expectedTools.get(variant) == tools.tools().size());
            }
        }
        expect("endpoint catalog larger than task catalog", bytes.get("endpoints") > bytes.get("good"));
        for (String registration : List.of("map", "list")) {
            Set<String> orders = new LinkedHashSet<>();
            for (int launch = 1; launch <= 5; launch++) {
                try (McpSyncClient client = open("logback-stderr.xml", "--variant=good", "--registration=" + registration)) {
                    String order = String.join(",", client.listTools().tools().stream().map(Tool::name).toList());
                    orders.add(order);
                    System.out.printf("  %-4s launch %d: %s%n", registration, launch, order);
                }
            }
            System.out.printf("  %-4s distinct orderings over 5 launches: %d%n", registration, orders.size());
            // Only the list is asserted: the map's count is random by design, and even 1 is a possible outcome.
            if ("list".equals(registration)) {
                expect("list registration keeps one order", 1 == orders.size());
            }
        }
    }

    // Mistake 4 ------------------------------------------------------------------------------

    private static void unboundedResults() {
        heading("4. Returning whatever the data happens to weigh");
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=bad", "--orders=5000")) {
            step("list_orders, 5,000 orders", "isError=false, text content 1,266,288 bytes",
                    () -> size(call(client, "list_orders", Map.of())));
        }
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=good", "--orders=5000")) {
            step("search_orders limit=20", "next_cursor=ORD-000020", () -> {
                CallToolResult first = call(client, "search_orders", Map.of("limit", 20));
                return size(first) + ", next_cursor=" + ((Map<?, ?>) first.structuredContent()).get("next_cursor");
            });
            step("search_orders last page", "isError=false, 10 orders, next_cursor=null", () -> {
                CallToolResult last = call(client, "search_orders", Map.of("limit", 20, "cursor", "ORD-004990"));
                // If the result carries no structured content, show what came back instead.
                if (last.structuredContent() instanceof Map<?, ?> page) {
                    return "isError=%s, %d orders, next_cursor=%s".formatted(
                            last.isError(), ((List<?>) page.get("orders")).size(), page.get("next_cursor"));
                }
                return describe(last);
            });
            step("search_orders limit=500", "must have a maximum value of 50",
                    () -> describe(call(client, "search_orders", Map.of("limit", 500))));
        }
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=bad", "--orders=120000")) {
            step("list_orders, 120,000 orders", TIMEOUT, () -> size(call(client, "list_orders", Map.of())));
            step("tools/list after that", TIMEOUT, () -> client.listTools().tools().size() + " tools");
        }
    }

    // Mistake 5 ------------------------------------------------------------------------------

    private static void requestsThatOutliveTheClient() throws InterruptedException {
        heading("5. Holding a request open for longer than the client waits");
        System.out.println("[bad] export takes 25 s; client requestTimeout is the SDK default (20 s)");
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=bad", "--export-seconds=25")) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                step("export_orders, attempt " + attempt, TIMEOUT, () -> describe(call(client, "export_orders", Map.of())));
                Thread.sleep(7_000);
                step("exports_written", "text=\"%d\"".formatted(attempt), () -> describe(call(client, "exports_written", Map.of())));
            }
        }
        System.out.println("[good] same 25 s export behind a handle and an idempotency key");
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=good", "--export-seconds=25")) {
            Map<String, Object> key = Map.of("idempotency_key", "nightly-2026-10-26");
            CallToolResult first = call(client, "start_export", key);
            System.out.printf("  %-28s -> %s%n", "start_export, attempt 1", describe(first));
            String handle = text(first).split(" ")[1];
            step("start_export, attempt 2", handle, () -> describe(call(client, "start_export", key)));
            String state = "";
            for (int poll = 1; poll <= 10 && false == state.endsWith("done."); poll++) {
                Thread.sleep(5_000);
                state = text(call(client, "export_status", Map.of("handle", handle)));
                System.out.printf("  %-28s -> %s%n", "export_status", state);
            }
            expect("export reaches done within 50 s", state.endsWith("done."));
            step("exports_written", "text=\"1\"", () -> describe(call(client, "exports_written", Map.of())));
        }
        System.out.println("[good, failing bucket] 1 s export; the first one fails");
        try (McpSyncClient client = open("logback-stderr.xml", "--variant=good", "--export-seconds=1", "--export-failures=1")) {
            Map<String, Object> key = Map.of("idempotency_key", "nightly-2026-10-27");
            String handle = text(call(client, "start_export", key)).split(" ")[1];
            Thread.sleep(2_000);
            step("export_status", "failed (reporting bucket unavailable)",
                    () -> describe(call(client, "export_status", Map.of("handle", handle))));
            CallToolResult retry = call(client, "start_export", key);
            System.out.printf("  %-28s -> %s%n", "start_export, same key", describe(retry));
            expect("retry after failure gets a new handle", false == text(retry).contains(handle));
            Thread.sleep(2_000);
            step("export_status", "done", () -> describe(call(client, "export_status", Map.of("handle", text(retry).split(" ")[1]))));
            step("exports_written", "text=\"1\"", () -> describe(call(client, "exports_written", Map.of())));
        }
    }

    // Plumbing -------------------------------------------------------------------------------

    private static McpSyncClient connect(String logbackConfig, String... serverArgs) {
        List<String> command = new ArrayList<>(List.of(
                "-Dlogback.configurationFile=" + logbackConfig,
                "-cp", jar(),
                "com.wolinski.mcpmistakes.server.DemoServer"));
        command.addAll(List.of(serverArgs));
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder(javaBinary()).args(command).build(), JSON);
        // Server logs arrive on stderr. Stack frames and the SDK's per-connection handshake line are
        // dropped from the transcript; every other line is shown.
        transport.setStdErrorHandler(line -> {
            if (false == line.startsWith("\tat ") && false == line.contains("Client initialize request")) {
                System.out.println("  [server stderr] " + line);
            }
        });
        return McpClient.sync(transport).build();
    }

    private static McpSyncClient open(String logbackConfig, String... serverArgs) {
        McpSyncClient client = connect(logbackConfig, serverArgs);
        client.initialize();
        return client;
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(CallToolRequest.builder(tool).arguments(arguments).build());
    }

    /** Runs one step, prints its transcript line, and checks the outcome contains what the article claims. */
    private static void step(String label, String expected, Callable<String> action) {
        long start = System.nanoTime();
        String outcome;
        try {
            outcome = action.call();
        }
        catch (McpError e) {
            outcome = "JSON-RPC error %d: %s".formatted(e.getJsonRpcError().code(), e.getJsonRpcError().message());
        }
        catch (Exception e) {
            outcome = "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage());
        }
        System.out.printf("  %-28s -> %s  (%.1f s)%n", label, outcome, (System.nanoTime() - start) / 1e9);
        expect("%s contains \"%s\"".formatted(label, expected), outcome.contains(expected));
    }

    private static void expect(String description, boolean holds) {
        if (holds) {
            met++;
            return;
        }
        unmet.add(description);
    }

    private static String describe(CallToolResult result) {
        return "isError=%s, text=\"%s\"".formatted(result.isError(), text(result));
    }

    private static String size(CallToolResult result) {
        return "isError=%s, text content %,d bytes".formatted(result.isError(), utf8(text(result)));
    }

    private static String text(CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (Content content : result.content()) {
            if (content instanceof TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void heading(String title) {
        System.out.printf("%n== %s%n", title);
    }

    private static String jar() {
        try {
            return Path.of(Scenarios.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command().orElse("java");
    }
}
