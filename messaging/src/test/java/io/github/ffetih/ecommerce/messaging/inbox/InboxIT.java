package io.github.ffetih.ecommerce.messaging.inbox;

import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.MessagingTestcontainers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.consume;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest
@Import(MessagingTestcontainers.class)
class InboxIT {

    static final String TOPIC = "inbox-it.steps";
    static final String DEAD_LETTER_TOPIC = DeadLetters.topicFor(TOPIC);
    static final String CONSUMER = "inbox-it.recording-consumer";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    Inbox inbox;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    RecordingConsumer consumer;

    @Autowired
    MeterRegistry meters;

    record Step(int failTimes) {
    }

    /** Topics exist before the listener starts, because auto-creation is off in these tests. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic steps() {
            return new NewTopic(TOPIC, 1, (short) 1);
        }

        @Bean
        NewTopic stepsDeadLetters() {
            return new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1);
        }

        @Bean
        RecordingConsumer recordingConsumer(Inbox inbox, EventReader reader, TransactionTemplate transactions,
                                            JdbcClient jdbc) {
            return new RecordingConsumer(inbox, reader, transactions, jdbc);
        }
    }

    /** A typical consumer: read the event, then inbox check and business change in one transaction. */
    static class RecordingConsumer {

        final Inbox inbox;
        final EventReader reader;
        final TransactionTemplate transactions;
        final JdbcClient jdbc;
        final Map<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();

        RecordingConsumer(Inbox inbox, EventReader reader, TransactionTemplate transactions, JdbcClient jdbc) {
            this.inbox = inbox;
            this.reader = reader;
            this.transactions = transactions;
            this.jdbc = jdbc;
        }

        @KafkaListener(topics = TOPIC, groupId = CONSUMER)
        public void on(String value) {
            EventEnvelope event = reader.read(value);
            Step step = reader.data(event, Step.class);
            int attempt = attempts.computeIfAbsent(event.eventId(), id -> new AtomicInteger()).incrementAndGet();

            transactions.executeWithoutResult(status -> {
                if (!inbox.markProcessed(CONSUMER, event.eventId())) {
                    return; // duplicate delivery
                }
                jdbc.sql("INSERT INTO it_effects (event_id) VALUES (:id)").param("id", event.eventId()).update();
                if (attempt <= step.failTimes()) {
                    // Fails after the inbox row and the effect were written: both must roll back,
                    // otherwise the retry would be mistaken for a duplicate and the effect lost.
                    throw new IllegalStateException("simulated transient failure, attempt " + attempt);
                }
            });
        }
    }

    @Test
    void redeliveredEventChangesStateOnlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String message = envelope(eventId, 0);

        kafka.send(TOPIC, "k", message).get();
        kafka.send(TOPIC, "k", message).get(); // same event delivered again

        awaitUntil(Duration.ofSeconds(15), () -> consumer.attempts.getOrDefault(eventId, new AtomicInteger()).get() == 2);
        assertThat(effects(eventId)).isEqualTo(1);
    }

    @Test
    void transientFailureIsRetriedAndTheRetryIsNotMistakenForADuplicate() throws Exception {
        UUID eventId = UUID.randomUUID();

        kafka.send(TOPIC, "k", envelope(eventId, 2)).get(); // fails twice, succeeds on the third attempt

        awaitUntil(Duration.ofSeconds(15), () -> effects(eventId) == 1);
        assertThat(consumer.attempts.get(eventId).get()).isEqualTo(3);
    }

    @Test
    void malformedMessageGoesToTheDeadLetterTopicAndTheNextMessageIsProcessed() throws Exception {
        String poison = "this is not json " + UUID.randomUUID();
        UUID next = UUID.randomUUID();
        double counted = deadLettersCounted("MalformedEventException");

        kafka.send(TOPIC, "k", poison).get();
        kafka.send(TOPIC, "k", envelope(next, 0)).get();

        awaitUntil(Duration.ofSeconds(15), () -> effects(next) == 1);

        List<ConsumerRecord<String, String>> deadLetters = consume(DEAD_LETTER_TOPIC, 1, Duration.ofSeconds(10)).stream()
                .filter(record -> poison.equals(record.value()))
                .toList();
        assertThat(deadLetters).hasSize(1);
        ConsumerRecord<String, String> deadLetter = deadLetters.getFirst();
        String reason = header(deadLetter, "kafka_dlt-exception-fqcn") + " " + header(deadLetter, "kafka_dlt-exception-cause-fqcn");
        assertThat(reason).contains("MalformedEventException");
        assertThat(header(deadLetter, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(deadLettersCounted("MalformedEventException")).isEqualTo(counted + 1);
    }

    /** Expiry is why the retention must outlive the topic's: a forgotten id counts as new again. */
    @Test
    void forgetsEventIdsOlderThanTheRetentionAndOnlyThose() {
        String consumer = "inbox-it.retention-" + UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        insertProcessed(consumer, old, Duration.ofDays(31));
        insertProcessed(consumer, recent, Duration.ofDays(29));

        assertThat(inbox.deleteExpired()).isGreaterThanOrEqualTo(1);

        assertThat(firstDelivery(consumer, old)).as("forgotten, so new again").isTrue();
        assertThat(firstDelivery(consumer, recent)).as("still remembered").isFalse();
    }

    @Test
    void refusesToRecordOutsideATransaction() {
        assertThatIllegalStateException().isThrownBy(() -> inbox.markProcessed(CONSUMER, UUID.randomUUID()));
    }

    private String envelope(UUID eventId, int failTimes) {
        return json.writeValueAsString(new EventEnvelope(
                eventId, "test.step", 1, Instant.now(), "inbox-it", json.valueToTree(new Step(failTimes))));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private int effects(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM it_effects WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single();
    }

    private double deadLettersCounted(String exception) {
        Counter counter = meters.find(DeadLetterCounter.METRIC).tag("topic", TOPIC).tag("exception", exception).counter();
        return counter == null ? 0 : counter.count();
    }

    private boolean firstDelivery(String consumer, UUID eventId) {
        Boolean first = transactions.execute(status -> inbox.markProcessed(consumer, eventId));
        return Boolean.TRUE.equals(first);
    }

    private void insertProcessed(String consumer, UUID eventId, Duration ago) {
        jdbc.sql("""
                        INSERT INTO inbox_messages (consumer, event_id, processed_at)
                        VALUES (:consumer, :eventId, now() - make_interval(secs => :seconds))
                        """)
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("seconds", ago.toSeconds())
                .update();
    }

    private static void awaitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }
}
