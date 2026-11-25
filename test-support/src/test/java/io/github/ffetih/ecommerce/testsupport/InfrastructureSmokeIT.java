package io.github.ffetih.ecommerce.testsupport;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that the build environment (local Docker or CI runner) can start the shared
 * infrastructure images and that both are usable, before any service depends on them.
 */
@Testcontainers
class InfrastructureSmokeIT {

    @Container
    static final PostgreSQLContainer POSTGRES = Infrastructure.postgres();

    @Container
    static final KafkaContainer KAFKA = Infrastructure.kafka();

    @Test
    void postgresAcceptsConnectionsAndRunsMajorVersion17() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW server_version")) {

            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).startsWith("17.");
        }
    }

    @Test
    void kafkaDeliversAProducedMessageToAConsumer() throws Exception {
        String topic = "smoke-" + UUID.randomUUID();

        Map<String, Object> producerConfig = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig)) {
            producer.send(new ProducerRecord<>(topic, "order-1", "hello")).get(10, TimeUnit.SECONDS);
        }

        Map<String, Object> consumerConfig = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "smoke-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig)) {
            consumer.subscribe(List.of(topic));

            ConsumerRecords<String, String> records = ConsumerRecords.empty();
            Instant deadline = Instant.now().plusSeconds(30);
            while (records.isEmpty() && Instant.now().isBefore(deadline)) {
                records = consumer.poll(Duration.ofMillis(500));
            }

            assertThat(records.count()).isEqualTo(1);
            ConsumerRecord<String, String> record = records.iterator().next();
            assertThat(record.key()).isEqualTo("order-1");
            assertThat(record.value()).isEqualTo("hello");
        }
    }
}
