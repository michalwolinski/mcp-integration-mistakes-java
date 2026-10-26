package com.wolinski.mcpmistakes.server;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Mistake 5: a side effect that outlives the request the client has already given up on. */
final class ExportTools {

    private final Duration work;

    private final AtomicInteger written = new AtomicInteger();

    private final Map<String, String> handleByKey = new ConcurrentHashMap<>();

    private final Map<String, String> statusByHandle = new ConcurrentHashMap<>();

    private final AtomicInteger failuresLeft;

    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();

    /** @param failures how many exports fail before one succeeds (0 in every scenario but the failure one) */
    ExportTools(Duration work, int failures) {
        this.work = work;
        this.failuresLeft = new AtomicInteger(failures);
    }

    /** Holds the request open for the whole export. */
    SyncToolSpecification blocking() {
        return Tools.tool("export_orders", "Export all orders to the reporting bucket.", """
                { "type": "object", "additionalProperties": false }
                """, (exchange, request) -> {
            runExport();
            return Tools.text("Export #%d written.".formatted(written.get()));
        });
    }

    /**
     * Returns a handle at once. The same idempotency key maps to the same export while it runs and after it
     * succeeds; a failed export releases its key, so a retry with that key starts a new one.
     */
    SyncToolSpecification starting() {
        return Tools.tool("start_export",
                "Start exporting all orders. Returns an export handle immediately; poll export_status with it. "
                        + "Reuse the same idempotency_key when retrying: while that export runs or after it is done, "
                        + "the key returns it instead of starting a second one. A failed export can be started again.", """
                {
                  "type": "object",
                  "properties": {
                    "idempotency_key": { "type": "string", "minLength": 8, "maxLength": 64 }
                  },
                  "required": ["idempotency_key"],
                  "additionalProperties": false
                }
                """, (exchange, request) -> {
            String key = (String) request.arguments().get("idempotency_key");
            String handle = handleByKey.computeIfAbsent(key, k -> {
                String h = "exp_" + UUID.randomUUID();
                statusByHandle.put(h, "running");
                jobs.submit(() -> {
                    try {
                        runExport();
                        statusByHandle.put(h, "done");
                    }
                    catch (RuntimeException e) {
                        statusByHandle.put(h, "failed (" + e.getMessage() + "); start it again");
                        handleByKey.remove(k, h);
                    }
                });
                return h;
            });
            return Tools.text("Export %s is %s.".formatted(handle, statusByHandle.get(handle)));
        });
    }

    SyncToolSpecification status() {
        return Tools.tool("export_status", "Report whether an export has finished.", """
                {
                  "type": "object",
                  "properties": { "handle": { "type": "string" } },
                  "required": ["handle"],
                  "additionalProperties": false
                }
                """, (exchange, request) -> {
            String handle = (String) request.arguments().get("handle");
            String state = statusByHandle.get(handle);
            if (null == state) {
                return Tools.error("Unknown export handle %s; start_export returns the handle.".formatted(handle));
            }
            return Tools.text("Export %s is %s.".formatted(handle, state));
        });
    }

    /** How many exports reached the bucket: the side effect the demo counts. */
    SyncToolSpecification count() {
        return Tools.tool("exports_written", "Count exports written to the reporting bucket.", """
                { "type": "object", "additionalProperties": false }
                """, (exchange, request) -> Tools.text(Integer.toString(written.get())));
    }

    private void runExport() {
        try {
            Thread.sleep(work);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("export interrupted", e);
        }
        if (0 < failuresLeft.getAndDecrement()) {
            throw new IllegalStateException("reporting bucket unavailable");
        }
        written.incrementAndGet();
    }
}
