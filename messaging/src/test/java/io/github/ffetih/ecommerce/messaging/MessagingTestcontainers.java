package io.github.ffetih.ecommerce.messaging;

import io.github.ffetih.ecommerce.testsupport.Infrastructure;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PostgreSQL and Kafka for the messaging tests. Topic auto-creation is disabled so a test can make
 * sends to one topic fail on purpose, and every test creates the topics it needs.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MessagingTestcontainers {

    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();
    static final KafkaContainer KAFKA = Infrastructure.kafka()
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return KAFKA;
    }

    public static String uniqueTopic(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static void createTopic(String topic, int partitions) {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create topic " + topic, e);
        }
    }

    /** Reads {@code expected} records from the beginning of the topic, or fails after the timeout. */
    public static List<ConsumerRecord<String, String>> consume(String topic, int expected, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (records.size() < expected && Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            // Poll a little longer to catch unexpected extra records (duplicates).
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        return records;
    }
}
