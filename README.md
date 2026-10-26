# MCP integration mistakes, reproduced in Java

By [Michał Woliński](https://wolinski.com). Companion code for the article
[Five MCP Integration Mistakes That Fail Somewhere Else](https://wolinski.com/blog/mcp-integration-mistakes-java.html).

Five ways an MCP server can be wired wrong without the mistake failing where it was made, each
reproduced against the official [Java MCP SDK](https://github.com/modelcontextprotocol/java-sdk)
and paired with its fix. A scenario runner plays the MCP client, starts each server variant as a
stdio subprocess and prints what the client actually received. Every snippet in the article is a
file in this repository, and every measurement in it comes from [`scenarios.log`](scenarios.log).

## Run it

Docker and bash are the only requirements. The images are pinned by digest.

```sh
./run.sh          # all five scenarios, about five minutes, rewrites scenarios.log
./run.sh 2 3      # a subset
```

Every step carries the outcome the article claims for it. The run ends with an `== expectations: N met, M unmet` line, and `run.sh` exits non-zero when any expectation is unmet or a container is left running, so an SDK upgrade that changes the behaviour shows up as a failed run, not as a quietly different transcript.

Without Docker: JDK 27 and Maven 3.10 (the versions in the image), then `mvn -q package && java -jar target/demo.jar`.

## Versions

| Component | Version |
| --- | --- |
| JDK | Temurin 27+35 (`eclipse-temurin:27-jre`) |
| Java MCP SDK | `io.modelcontextprotocol.sdk:mcp` 2.1.0 |
| Protocol revision negotiated | 2025-11-25 |
| Logback | 1.5.38 |

## Claims and the evidence for them

| Claim | Scenario | Where to look |
| --- | --- | --- |
| A Logback `ConsoleAppender` without `<target>` breaks the stdio handshake, because the SDK logs the initialize request at INFO before it answers | 1 | `logback-stdout.xml`, first block of `scenarios.log` |
| At WARN the handshake survives and the first warning kills the session; every later call waits out the 20 s timeout | 1 | `logback-stdout-warn.xml`, `StockTools` |
| `<target>System.err</target>`, or reserving the real stdout for the transport, keeps the channel clean | 1 | `logback-stderr.xml`, `DemoServer.guardedTransport()` |
| An exception thrown from a tool handler reaches the client as JSON-RPC error -32603 carrying only the exception message | 2 | `CancelOrderTools.throwing` |
| A business failure returned as `isError: true` carries the id, the reason and the next step | 2 | `CancelOrderTools.reporting` |
| A generated 1:1 endpoint catalog for the whole API (35 tools) is 7.7x the bytes of a task-shaped one for this agent (7 tools): 5x from scope, 1.5x from tool shape | 3 | `EndpointCatalog`, `DemoServer` |
| Registering tools through `Map.of` changes their order between JVM launches; a `List` does not | 3 | `DemoServer` (`--registration=map`) |
| An unbounded `list_orders` returns 1.27 MB for 5,000 orders, and past 16 Mi characters the stdio client stops reading altogether | 4 | `OrderListingTools.unbounded` |
| A bounded, cursor-paged `search_orders` returns about 2.4 KB per page, its limit is enforced by the input schema, and its last page (`next_cursor: null`) passes the client's `outputSchema` check | 4 | `OrderListingTools.paged` |
| A client timeout does not stop the server: the export still lands, and a retry lands a second one | 5 | `ExportTools.blocking` |
| A handle plus an idempotency key turns two attempts into one export | 5 | `ExportTools.starting` |
| A failed export is reported as failed and releases its key, so a retry with the same key starts a new export | 5 | `ExportTools.starting`, `--export-failures` |

## Layout

```
src/main/java/com/wolinski/mcpmistakes/
  client/Scenarios.java          the MCP client: launches servers, prints the transcript
  server/DemoServer.java         the stdio MCP server and its variants
  server/StockTools.java         mistake 1
  server/CancelOrderTools.java   mistake 2
  server/EndpointCatalog.java    mistake 3 (catalog only; handlers are stubs)
  server/OrderListingTools.java  mistake 4
  server/ExportTools.java        mistake 5
  domain/                        a deterministic in-memory order book
src/main/resources/              Logback: three server configurations, one for the client
```

## Author

Michał Woliński, [wolinski.com](https://wolinski.com).

## License

MIT, see [LICENSE](LICENSE).
