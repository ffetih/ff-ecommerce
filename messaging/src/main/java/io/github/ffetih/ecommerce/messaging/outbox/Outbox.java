package io.github.ffetih.ecommerce.messaging.outbox;

import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records an event in the caller's database transaction. The event becomes visible to the relay
 * only if that transaction commits, so "state changed" and "event recorded" happen together or not at all.
 *
 * <p>The row also keeps the W3C {@code traceparent} of the work that wrote it (an HTTP request, a Kafka
 * listener, a scheduled job). The relay sends the event later, from its own thread, where that context is
 * gone; with the stored value it continues the same trace instead of starting a new one (ADR-0011).
 */
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    private final String source;
    private final ObservationRegistry observations;

    public Outbox(JdbcClient jdbc, JsonMapper json, Clock clock, String source, ObservationRegistry observations) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.source = source;
        this.observations = observations;
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
                        INSERT INTO outbox_messages (event_id, topic, message_key, event_type, payload, traceparent)
                        VALUES (:eventId, :topic, :key, :eventType, CAST(:payload AS jsonb), :traceparent)
                        """)
                .param("eventId", envelope.eventId())
                .param("topic", topic)
                .param("key", key)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(envelope))
                .param("traceparent", traceparent(topic, eventType))
                .update();
        return envelope.eventId();
    }

    /**
     * A short "outbox append" span, child of whatever is current, whose context the tracer writes into a
     * map the same way it would write Kafka headers. Without tracing the map stays empty and the column
     * stays NULL; the relay then simply starts a fresh trace.
     */
    private String traceparent(String topic, String eventType) {
        Map<String, String> carrier = new HashMap<>();
        var context = new SenderContext<Map<String, String>>(Map::put);
        context.setCarrier(carrier);
        Observation.createNotStarted("outbox.append", () -> context, observations)
                .contextualName("outbox append " + eventType)
                .lowCardinalityKeyValue("topic", topic)
                .start()
                .stop();
        return carrier.get(OutboxRelay.TRACEPARENT);
    }
}
