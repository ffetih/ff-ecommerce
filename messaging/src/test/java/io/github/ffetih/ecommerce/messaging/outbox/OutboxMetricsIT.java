package io.github.ffetih.ecommerce.messaging.outbox;

import io.github.ffetih.ecommerce.messaging.MessagingTestcontainers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox lag as a monitoring system would read it. Other tests share the table, so the assertions
 * compare with the values before the test, and the waiting message here is older than any of theirs.
 */
@SpringBootTest
@Import(MessagingTestcontainers.class)
class OutboxMetricsIT {

    private static final Duration WAITING_FOR = Duration.ofDays(30);

    @Autowired
    OutboxMetrics metrics;

    @Autowired
    JdbcClient jdbc;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void bind() {
        metrics.bindTo(registry);
    }

    @AfterEach
    void removeTheTestMessage() {
        jdbc.sql("DELETE FROM outbox_messages WHERE event_id = :eventId").param("eventId", eventId).update();
    }

    @Test
    void aMessageThatIsNotPublishedShowsAsPendingAndAgesUntilItIsPublished() {
        double pendingBefore = pending();

        // Written long ago and not due again for a day, so no relay picks it up during the test.
        jdbc.sql("""
                        INSERT INTO outbox_messages (event_id, topic, message_key, event_type, payload, created_at, next_attempt_at)
                        VALUES (:eventId, 'metrics-it', 'k', 'test.step', '{}'::jsonb,
                                now() - make_interval(secs => :ageSeconds), now() + interval '1 day')
                        """)
                .param("eventId", eventId)
                .param("ageSeconds", WAITING_FOR.toSeconds())
                .update();

        assertThat(pending()).isEqualTo(pendingBefore + 1);
        assertThat(oldestAgeSeconds()).isGreaterThanOrEqualTo(WAITING_FOR.toSeconds());

        jdbc.sql("UPDATE outbox_messages SET published_at = now() WHERE event_id = :eventId")
                .param("eventId", eventId)
                .update();

        assertThat(pending()).isEqualTo(pendingBefore);
        assertThat(oldestAgeSeconds()).isLessThan(WAITING_FOR.toSeconds());
    }

    private double pending() {
        return registry.get(OutboxMetrics.PENDING).gauge().value();
    }

    private double oldestAgeSeconds() {
        return registry.get(OutboxMetrics.OLDEST_PENDING_AGE).timeGauge().value(TimeUnit.SECONDS);
    }
}
