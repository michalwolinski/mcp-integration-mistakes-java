package com.wolinski.mcpmistakes.server;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.ArrayList;
import java.util.List;

/**
 * Mistake 3: the REST API mapped one endpoint to one tool, the way an OpenAPI-to-MCP generator does it.
 * Only the catalog matters for the measurement, so every handler answers with the same stub.
 */
final class EndpointCatalog {

    private record Resource(String path, String singular, String fields) {
    }

    private static final List<Resource> RESOURCES = List.of(
            new Resource("orders", "order", "customer_id, status, currency, shipping_address, notes"),
            new Resource("customers", "customer", "name, email, phone, billing_address, tax_id"),
            new Resource("products", "product", "sku, name, description, price, currency, weight_grams"),
            new Resource("shipments", "shipment", "order_id, carrier, tracking_number, shipped_at"),
            new Resource("returns", "return", "order_id, reason, items, refund_method"),
            new Resource("invoices", "invoice", "order_id, issued_at, due_at, amount, currency"),
            new Resource("warehouses", "warehouse", "code, name, address, capacity"));

    private static final String LIST_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "page": { "type": "integer", "minimum": 1, "description": "Page number, starting at 1." },
                "per_page": { "type": "integer", "minimum": 1, "maximum": 500, "description": "Items per page." },
                "sort": { "type": "string", "description": "Field to sort by. Prefix with - for descending order." },
                "fields": { "type": "string", "description": "Comma-separated list of fields to return (sparse fieldset)." },
                "include": { "type": "string", "description": "Comma-separated list of related resources to embed." },
                "filter": { "type": "object", "additionalProperties": { "type": "string" }, "description": "Field filters, e.g. {\\"status\\": \\"paid\\"}." },
                "updated_since": { "type": "string", "format": "date-time", "description": "Only items changed after this instant." }
              },
              "additionalProperties": false
            }
            """;

    private static final String ID_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "Identifier of the %s." },
                "include": { "type": "string", "description": "Comma-separated list of related resources to embed." }
              },
              "required": ["id"],
              "additionalProperties": false
            }
            """;

    private static final String BODY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "Identifier of the %s (update only)." },
                "body": { "type": "object", "description": "Request body. Fields: %s." },
                "idempotency_key": { "type": "string", "description": "Optional Idempotency-Key header value." },
                "if_match": { "type": "string", "description": "Optional If-Match header value (ETag)." }
              },
              "required": ["body"],
              "additionalProperties": false
            }
            """;

    private EndpointCatalog() {
    }

    static List<SyncToolSpecification> tools() {
        List<SyncToolSpecification> tools = new ArrayList<>();
        for (Resource r : RESOURCES) {
            tools.add(stub("list_" + r.path(), "GET /v1/%s - List %s. Supports pagination, sorting, sparse fieldsets, includes and filters."
                    .formatted(r.path(), r.path()), LIST_SCHEMA));
            tools.add(stub("get_" + r.singular(), "GET /v1/%s/{id} - Retrieve a single %s by id."
                    .formatted(r.path(), r.singular()), ID_SCHEMA.formatted(r.singular())));
            tools.add(stub("create_" + r.singular(), "POST /v1/%s - Create a %s. Returns 201 with the created resource."
                    .formatted(r.path(), r.singular()), BODY_SCHEMA.formatted(r.singular(), r.fields())));
            tools.add(stub("update_" + r.singular(), "PATCH /v1/%s/{id} - Partially update a %s. Send only changed fields."
                    .formatted(r.path(), r.singular()), BODY_SCHEMA.formatted(r.singular(), r.fields())));
            tools.add(stub("delete_" + r.singular(), "DELETE /v1/%s/{id} - Delete a %s. Returns 204 on success."
                    .formatted(r.path(), r.singular()), ID_SCHEMA.formatted(r.singular())));
        }
        return tools;
    }

    private static SyncToolSpecification stub(String name, String description, String schema) {
        return Tools.tool(name, description, schema, (exchange, request) -> Tools.text("stub: catalog-size demo only"));
    }
}
