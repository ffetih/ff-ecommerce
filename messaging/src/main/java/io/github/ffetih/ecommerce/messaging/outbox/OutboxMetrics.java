package io.github.ffetih.ecommerce.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.concurrent.TimeUnit;

/**
 * How far the relay is behind. A healthy outbox drains within a second; a growing age means events are
 * written but not reaching Kafka (broker down, a key stuck behind a failing message), so other services
 * stop hearing about this one's changes.
 *
 * <p>Both values are read from the database whenever metrics are collected. The query only touches
 * unpublished rows (the partial index {@code outbox_messages_pending}), and age is measured with the
 * database clock, the same one that wrote {@code created_at}. A failed read reports NaN rather than a
 * reassuring zero.
 */
public class OutboxMetrics implements MeterBinder {

    public static final String PENDING = "outbox.pending";
    public static final String OLDEST_PENDING_AGE = "outbox.oldest.pending.age";

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final JdbcClient jdbc;

    public OutboxMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING, this, OutboxMetrics::pending)
                .description("Outbox messages not yet published to Kafka")
                .baseUnit("messages")
                .register(registry);
        TimeGauge.builder(OLDEST_PENDING_AGE, this, TimeUnit.SECONDS, OutboxMetrics::oldestPendingAgeSeconds)
                .description("How long the oldest unpublished outbox message has been waiting; 0 when none waits")
                .register(registry);
    }

    double pending() {
        return read("SELECT count(*) FROM outbox_messages WHERE published_at IS NULL");
    }

    double oldestPendingAgeSeconds() {
        return read("""
                SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)
                FROM outbox_messages
                WHERE published_at IS NULL
                """);
    }

    private double read(String sql) {
        try {
            return jdbc.sql(sql).query(Double.class).single();
        } catch (DataAccessException e) {
            log.warn("Could not read outbox metrics", e);
            return Double.NaN;
        }
    }
}
