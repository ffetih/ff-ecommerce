package io.github.ffetih.ecommerce.messaging.outbox;

import io.github.ffetih.ecommerce.messaging.MessagingProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka, preserving order per (topic, key), with any number of relay instances.
 *
 * <h2>How one run works</h2>
 * <ol>
 *   <li>Claim the <em>oldest unpublished row of each (topic, key)</em> ("heads") with
 *       {@code FOR UPDATE SKIP LOCKED}. Another relay skips rows we hold and takes other keys.</li>
 *   <li>For each head, lock the rows queued behind it for the same key. No other relay can take them:
 *       they are not heads while our head is unpublished.</li>
 *   <li>Send them one by one. On the first failure, stop that key: later events of the key wait,
 *       so a consumer never sees event n+1 before event n. Other keys carry on.</li>
 * </ol>
 * Delivery is at least once: a crash between "sent" and "marked published" sends the row again.
 * Consumers deduplicate by event id. Failed rows are retried with exponential backoff and never
 * dropped, because silently losing an event would leave services permanently inconsistent.
 *
 * <h2>Tracing</h2>
 * Each send runs inside an "outbox relay" span whose parent is the {@code traceparent} stored with the
 * row ({@link Outbox}). The Kafka producer span is created under it and writes its own traceparent into
 * the record headers, so the consumer continues the trace of the request that caused the event, however
 * long the row waited.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** W3C Trace Context header name; also the outbox column that stores it. */
    static final String TRACEPARENT = "traceparent";

    private static final String CLAIM_HEADS = """
            SELECT id, event_id, topic, message_key, event_type, payload::text AS payload, attempts, traceparent
            FROM outbox_messages o
            WHERE o.published_at IS NULL
              AND o.next_attempt_at <= now()
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_messages earlier
                  WHERE earlier.topic = o.topic
                    AND earlier.message_key = o.message_key
                    AND earlier.published_at IS NULL
                    AND earlier.id < o.id)
            ORDER BY o.id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private static final String LOCK_QUEUED_BEHIND = """
            SELECT id, event_id, topic, message_key, event_type, payload::text AS payload, attempts, traceparent
            FROM outbox_messages
            WHERE topic = :topic AND message_key = :key AND published_at IS NULL AND id > :headId
            ORDER BY id
            LIMIT :limit
            FOR UPDATE
            """;

    private static final int CLEANUP_BATCH = 1000;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final MessagingProperties.Outbox settings;
    private final ObservationRegistry observations;

    private volatile ScheduledExecutorService scheduler;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate transactions,
                       MessagingProperties.Outbox settings, ObservationRegistry observations) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = transactions;
        this.settings = settings;
        this.observations = observations;
    }

    /** One relay pass in one database transaction. Returns the number of messages published. */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            List<OutboxRow> heads = query(CLAIM_HEADS, settings.batchSize(), null);
            int count = 0;
            for (OutboxRow head : heads) {
                List<OutboxRow> queue = new ArrayList<>();
                queue.add(head);
                queue.addAll(query(LOCK_QUEUED_BEHIND, settings.maxPerKey() - 1, head));
                count += publishInOrder(queue);
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** Deletes published rows older than the retention period, in bounded batches until none are left. */
    public int deletePublishedBeforeRetention() {
        int total = 0;
        int deleted;
        do {
            deleted = deleteOneBatch();
            total += deleted;
        } while (deleted == CLEANUP_BATCH);
        return total;
    }

    private int deleteOneBatch() {
        return jdbc.sql("""
                        DELETE FROM outbox_messages
                        WHERE id IN (
                            SELECT id FROM outbox_messages
                            WHERE published_at < now() - make_interval(secs => :retentionSeconds)
                            LIMIT :batch)
                        """)
                .param("retentionSeconds", settings.retention().toSeconds())
                .param("batch", CLEANUP_BATCH)
                .update();
    }

    private int publishInOrder(List<OutboxRow> queue) {
        int published = 0;
        for (OutboxRow row : queue) {
            try {
                send(row);
            } catch (Exception failure) {
                markFailed(row, failure);
                return published; // keep the rest of this key queued behind the failed row
            }
            jdbc.sql("UPDATE outbox_messages SET published_at = now(), last_error = NULL WHERE id = :id")
                    .param("id", row.id())
                    .update();
            published++;
        }
        return published;
    }

    private void send(OutboxRow row) throws Exception {
        // The stored traceparent plays the role of an incoming header: the span continues that trace.
        var context = new ReceiverContext<OutboxRow>((stored, key) -> TRACEPARENT.equals(key) ? stored.traceparent() : null);
        context.setCarrier(row);
        Observation.createNotStarted("outbox.relay", () -> context, observations)
                .contextualName("outbox relay " + row.eventType())
                .lowCardinalityKeyValue("topic", row.topic())
                .observeChecked(() -> {
                    var record = new ProducerRecord<>(row.topic(), row.key(), row.payload());
                    record.headers().add("event-id", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
                    record.headers().add("event-type", row.eventType().getBytes(StandardCharsets.UTF_8));
                    kafka.send(record).get(settings.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                });
    }

    private void markFailed(OutboxRow row, Exception failure) {
        int attempt = row.attempts() + 1;
        Duration delay = backoff(attempt);
        log.warn("Outbox message {} ({} / {}) failed on attempt {}; retrying in {}. Later events for this key wait.",
                row.eventId(), row.topic(), row.key(), attempt, delay, failure);
        jdbc.sql("""
                        UPDATE outbox_messages
                        SET attempts = :attempts,
                            next_attempt_at = now() + make_interval(secs => :delaySeconds),
                            last_error = :error
                        WHERE id = :id
                        """)
                .param("attempts", attempt)
                .param("delaySeconds", delay.toMillis() / 1000.0)
                .param("error", String.valueOf(failure))
                .param("id", row.id())
                .update();
    }

    Duration backoff(int attempt) {
        long factor = 1L << Math.min(attempt - 1, 20);
        Duration delay = settings.initialBackoff().multipliedBy(factor);
        return delay.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : delay;
    }

    private List<OutboxRow> query(String sql, int limit, OutboxRow head) {
        if (limit <= 0) {
            return List.of();
        }
        var statement = jdbc.sql(sql).param("limit", limit);
        if (head != null) {
            statement = statement.param("topic", head.topic()).param("key", head.key()).param("headId", head.id());
        }
        return statement.query((rs, rowNum) -> new OutboxRow(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getString("traceparent"))).list();
    }

    private record OutboxRow(long id, UUID eventId, String topic, String key, String eventType,
                             String payload, int attempts, String traceparent) {
    }

    // --- background scheduling -------------------------------------------------------------------

    @Override
    public void start() {
        if (!settings.relayEnabled()) {
            log.info("Outbox relay is disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("outbox-relay").factory());
        long pollMillis = settings.pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::drain, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::cleanUp, 1, 60, TimeUnit.MINUTES);
    }

    private void drain() {
        try {
            // Keep going while runs are productive; pause for one poll interval once caught up.
            while (relayOnce() > 0 && !Thread.currentThread().isInterrupted()) {
                // next run immediately
            }
        } catch (Exception e) {
            log.error("Outbox relay run failed; will retry", e);
        }
    }

    private void cleanUp() {
        try {
            int deleted = deletePublishedBeforeRetention();
            if (deleted > 0) {
                log.info("Deleted {} published outbox messages older than {}", deleted, settings.retention());
            }
        } catch (Exception e) {
            log.error("Outbox cleanup failed; will retry", e);
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdown();
            try {
                current.awaitTermination(settings.sendTimeout().toMillis() * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
