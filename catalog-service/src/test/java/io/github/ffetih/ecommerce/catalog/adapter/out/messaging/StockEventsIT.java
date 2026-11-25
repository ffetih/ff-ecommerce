package io.github.ffetih.ecommerce.catalog.adapter.out.messaging;

import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.domain.InsufficientStockException;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.support.TestcontainersConfiguration;
import io.github.ffetih.ecommerce.events.catalog.StockChanged;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.ffetih.ecommerce.catalog.support.TestData.line;
import static io.github.ffetih.ecommerce.catalog.support.TestData.productWithStock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End to end through the real relay: stock changes are committed together with their events, and the
 * events reach Kafka in commit order for the SKU. A failed operation publishes nothing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockEventsIT {

    @Autowired
    ProductService products;

    @Autowired
    StockService stock;

    @Autowired
    ReservationService reservations;

    @Autowired
    JsonMapper json;

    @Test
    void everyStockChangeIsPublishedInOrderAndFailedOperationsPublishNothing() {
        Sku sku = productWithStock(products, stock, 10);            // RECEIVED   10 / 0 / 10
        UUID shipped = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();

        reservations.reserve(shipped, List.of(line(sku, 3)));        // RESERVED   10 / 3 / 7
        reservations.reserve(cancelled, List.of(line(sku, 2)));      // RESERVED   10 / 5 / 5
        assertThatThrownBy(() -> reservations.reserve(UUID.randomUUID(), List.of(line(sku, 50))))
                .isInstanceOf(InsufficientStockException.class);   // rolled back: no event
        reservations.release(cancelled);                             // RELEASED   10 / 3 / 7
        reservations.commit(shipped);                                // COMMITTED   7 / 0 / 7
        reservations.commit(shipped);                                // retry: no change, no event

        List<StockChanged> events = eventsFor(sku, 5, Duration.ofSeconds(20));

        assertThat(events).extracting(StockChanged::reason).containsExactly(
                StockChanged.RECEIVED, StockChanged.RESERVED, StockChanged.RESERVED,
                StockChanged.RELEASED, StockChanged.COMMITTED);
        assertThat(events).extracting(StockChanged::available).containsExactly(10, 7, 5, 7, 7);
        assertThat(events.getLast().onHand()).isEqualTo(7);
        assertThat(events.get(1).reference()).isEqualTo(shipped);
    }

    /** Reads the whole topic and keeps this SKU's events; waits a little longer to catch unexpected extras. */
    private List<StockChanged> eventsFor(Sku sku, int expected, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "stock-events-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<StockChanged> events = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(StockChanged.TOPIC));
            Instant deadline = Instant.now().plus(timeout);
            Instant settleUntil = null;
            while (Instant.now().isBefore(deadline) && (settleUntil == null || Instant.now().isBefore(settleUntil))) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (!sku.value().equals(record.key())) {
                        continue;
                    }
                    EventEnvelope envelope = json.readValue(record.value(), EventEnvelope.class);
                    assertThat(envelope.eventType()).isEqualTo(StockChanged.TYPE);
                    assertThat(envelope.source()).isEqualTo("catalog-service");
                    events.add(json.treeToValue(envelope.data(), StockChanged.class));
                }
                if (events.size() >= expected && settleUntil == null) {
                    settleUntil = Instant.now().plusSeconds(2);
                }
            }
        }
        return events;
    }
}
