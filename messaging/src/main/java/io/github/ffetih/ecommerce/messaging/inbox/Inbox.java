package io.github.ffetih.ecommerce.messaging.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Makes a consumer idempotent. Call {@link #markProcessed} first thing inside the consumer's
 * transaction, and only apply the change if it returns {@code true}.
 * <pre>
 *   transactions.inTransaction(() -> {
 *       if (!inbox.markProcessed("order-service.stock", event.eventId())) {
 *           return;                                  // seen before: do nothing
 *       }
 *       ... apply the change ...                     // fails? the inbox row rolls back too
 *   });
 * </pre>
 * Two concurrent deliveries of one event cannot both pass: the second insert waits on the primary key
 * until the first transaction ends, and then finds the row.
 */
public class Inbox {

    private final JdbcClient jdbc;

    public Inbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns {@code true} the first time this consumer sees the event, {@code false} for every redelivery. */
    public boolean markProcessed(String consumer, UUID eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inbox.markProcessed must run inside the consumer's transaction");
        }
        int inserted = jdbc.sql("""
                        INSERT INTO inbox_messages (consumer, event_id) VALUES (:consumer, :eventId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update();
        return inserted == 1;
    }
}
