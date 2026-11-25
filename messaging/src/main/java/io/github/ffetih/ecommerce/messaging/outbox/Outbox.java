package io.github.ffetih.ecommerce.messaging.outbox;

import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Records an event in the caller's database transaction. The event becomes visible to the relay
 * only if that transaction commits, so "state changed" and "event recorded" happen together or not at all.
 */
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final String source;

    public Outbox(JdbcClient jdbc, JsonMapper json, Clock clock, String source) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.source = source;
    }

    /**
     * Appends an event for {@code topic}. Events with the same {@code key} are delivered to Kafka in
     * the order they were appended.
     *
     * @return the generated event id
     * @throws IllegalStateException if no transaction is active: an outbox write outside a transaction
     *                               could be committed without the business change, or the reverse
     */
    public UUID append(String topic, String key, String eventType, int eventVersion, Object data) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox.append must run inside the business transaction");
        }
        var envelope = new EventEnvelope(
                UUID.randomUUID(), eventType, eventVersion, clock.instant(), source, json.valueToTree(data));

        jdbc.sql("""
                        INSERT INTO outbox_messages (event_id, topic, message_key, event_type, payload)
                        VALUES (:eventId, :topic, :key, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("eventId", envelope.eventId())
                .param("topic", topic)
                .param("key", key)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(envelope))
                .update();
        return envelope.eventId();
    }
}
