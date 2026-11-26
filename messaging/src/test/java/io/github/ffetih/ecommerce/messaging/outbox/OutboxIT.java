package io.github.ffetih.ecommerce.messaging.outbox;

import io.github.ffetih.ecommerce.messaging.MessagingTestcontainers;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.consume;
import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.createTopic;
import static io.github.ffetih.ecommerce.messaging.MessagingTestcontainers.uniqueTopic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@SpringBootTest
@Import(MessagingTestcontainers.class)
class OutboxIT {

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    record Step(String key, int sequence) {
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        assertThatIllegalStateException()
                .isThrownBy(() -> outbox.append("any-topic", "k", "test.step", 1, new Step("k", 1)));
    }

    @Test
    void rolledBackBusinessTransactionLeavesNoEvent() {
        String topic = uniqueTopic("rollback");

        try {
            transactions.executeWithoutResult(status -> {
                outbox.append(topic, "k", "test.step", 1, new Step("k", 1));
                throw new IllegalStateException("business rule failed after the event was written");
            });
        } catch (IllegalStateException expected) {
            // the transaction rolled back
        }

        assertThat(pendingCount(topic)).isZero();
    }

    @Test
    void twoCompetingRelaysPublishEveryEventOnceAndInOrderPerKey() throws Exception {
        String topic = uniqueTopic("ordering");
        createTopic(topic, 3);
        int keys = 10;
        int perKey = 20;
        for (int sequence = 1; sequence <= perKey; sequence++) {
            for (int k = 0; k < keys; k++) {
                String key = "key-" + k;
                int seq = sequence;
                transactions.executeWithoutResult(status ->
                        outbox.append(topic, key, "test.step", 1, new Step(key, seq)));
            }
        }

        // The same bean called from two threads behaves like two relay instances: two transactions
        // competing for the same rows.
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> relays = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                relays.add(executor.submit(() -> drainUntilEmpty(topic)));
            }
            for (Future<?> relayRun : relays) {
                relayRun.get();
            }
        }

        List<ConsumerRecord<String, String>> records = consume(topic, keys * perKey, Duration.ofSeconds(30));
        assertThat(records).hasSize(keys * perKey);
        assertThat(eventIds(records)).hasSize(keys * perKey); // no duplicates

        Map<String, List<Integer>> sequencesByKey = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            JsonNode envelope = json.readTree(record.value());
            assertThat(envelope.get("eventType").asString()).isEqualTo("test.step");
            sequencesByKey.computeIfAbsent(record.key(), k -> new ArrayList<>())
                    .add(envelope.get("data").get("sequence").asInt());
        }
        assertThat(sequencesByKey).hasSize(keys);
        sequencesByKey.values().forEach(sequences -> assertThat(sequences).isSorted().hasSize(perKey));
    }

    @Test
    void aFailingEventBlocksOnlyItsOwnKeyAndIsRetriedInOrder() throws Exception {
        String missingTopic = uniqueTopic("not-created-yet");
        String healthyTopic = uniqueTopic("healthy");
        createTopic(healthyTopic, 1);
        transactions.executeWithoutResult(status -> {
            outbox.append(missingTopic, "order-1", "test.step", 1, new Step("order-1", 1));
            outbox.append(missingTopic, "order-1", "test.step", 1, new Step("order-1", 2));
            outbox.append(healthyTopic, "order-2", "test.step", 1, new Step("order-2", 1));
        });

        Instant started = Instant.now();
        relay.relayOnce();

        // The attempt on the missing topic gave up after the send timeout (2 s here), not after Kafka's
        // default max.block.ms of 60 s: that wait would hold every other key too (ProducerBlockLimit).
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(10));

        // The healthy key went through; the failed key is parked with its successor queued behind it.
        assertThat(consume(healthyTopic, 1, Duration.ofSeconds(10))).hasSize(1);
        assertThat(pendingCount(missingTopic)).isEqualTo(2);
        assertThat(attemptsOfOldestPending(missingTopic)).isEqualTo(1);

        // The problem is fixed (the topic now exists); after the backoff both events go out, in order.
        createTopic(missingTopic, 1);
        Instant deadline = Instant.now().plusSeconds(15);
        while (pendingCount(missingTopic) > 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(250);
            relay.relayOnce();
        }

        List<ConsumerRecord<String, String>> records = consume(missingTopic, 2, Duration.ofSeconds(10));
        assertThat(records).extracting(r -> json.readTree(r.value()).get("data").get("sequence").asInt())
                .containsExactly(1, 2);
    }

    @Test
    void retryDelayGrowsExponentiallyUpToTheMaximum() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofMillis(400));
        assertThat(relay.backoff(3)).isEqualTo(Duration.ofMillis(800));
        assertThat(relay.backoff(30)).isEqualTo(Duration.ofMinutes(5));
    }

    private void drainUntilEmpty(String topic) {
        Instant deadline = Instant.now().plusSeconds(30);
        while (pendingCount(topic) > 0 && Instant.now().isBefore(deadline)) {
            relay.relayOnce();
        }
    }

    private int pendingCount(String topic) {
        return jdbc.sql("SELECT count(*) FROM outbox_messages WHERE topic = :topic AND published_at IS NULL")
                .param("topic", topic).query(Integer.class).single();
    }

    private int attemptsOfOldestPending(String topic) {
        return jdbc.sql("""
                        SELECT attempts FROM outbox_messages
                        WHERE topic = :topic AND published_at IS NULL ORDER BY id LIMIT 1
                        """)
                .param("topic", topic).query(Integer.class).single();
    }

    private static Set<UUID> eventIds(List<ConsumerRecord<String, String>> records) {
        Set<UUID> ids = new HashSet<>();
        for (ConsumerRecord<String, String> record : records) {
            ids.add(UUID.fromString(new String(record.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8)));
        }
        return ids;
    }
}
