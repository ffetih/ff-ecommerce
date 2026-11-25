package io.github.ffetih.ecommerce.messaging.inbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
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
 * <p>
 * Event ids are remembered for the configured retention, which is longer than Kafka keeps the events
 * ({@code MessagingProperties}); {@link InboxCleanup} forgets older ones.
 */
public class Inbox {

    private static final int DELETE_BATCH = 1000;

    private final JdbcClient jdbc;
    private final Duration retention;

    public Inbox(JdbcClient jdbc, Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
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

    /** Forgets event ids processed before the retention period, in short transactions. Returns how many. */
    public int deleteExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.sql("""
                            DELETE FROM inbox_messages
                            WHERE (consumer, event_id) IN (
                                SELECT consumer, event_id FROM inbox_messages
                                WHERE processed_at < now() - make_interval(secs => :retentionSeconds)
                                LIMIT :batch)
                            """)
                    .param("retentionSeconds", retention.toSeconds())
                    .param("batch", DELETE_BATCH)
                    .update();
            total += deleted;
        } while (deleted == DELETE_BATCH);
        return total;
    }
}
