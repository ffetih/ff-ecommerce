package io.github.ffetih.ecommerce.messaging;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The JSON document every event travels in (ADR-0006).
 * <pre>
 * {
 *   "eventId": "...",                        unique per event; consumers deduplicate on it
 *   "eventType": "catalog.stock-changed",    stable name, never a Java class name
 *   "eventVersion": 1,                       incremented on breaking changes to "data"
 *   "occurredAt": "2026-11-25T10:00:00Z",
 *   "source": "catalog-service",
 *   "data": { ... }                          the event's own fields
 * }
 * </pre>
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String source,
        JsonNode data) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(data, "data is required");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }
}
