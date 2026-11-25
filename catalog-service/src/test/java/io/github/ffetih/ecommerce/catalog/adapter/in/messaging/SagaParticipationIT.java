package io.github.ffetih.ecommerce.catalog.adapter.in.messaging;

import io.github.ffetih.ecommerce.catalog.application.NotFoundException;
import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.support.TestcontainersConfiguration;
import io.github.ffetih.ecommerce.events.catalog.StockReservationFailed;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static io.github.ffetih.ecommerce.catalog.support.TestData.productWithStock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog as a saga participant, driven only through Kafka: the test plays order-service and
 * shipping-service by publishing their events, and observes catalog's database and its replies.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SagaParticipationIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    ProductService products;

    @Autowired
    StockService stock;

    @Autowired
    ReservationService reservations;

    @Test
    void orderPlacedReservesTheStockAndAnnouncesIt() throws Exception {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();

        publishOrderPlaced(UUID.randomUUID(), orderId, sku, 3);

        awaitUntil(() -> stock.get(sku).reserved() == 3);
        List<EventEnvelope> replies = repliesFor(orderId, 1);
        assertThat(replies).extracting(EventEnvelope::eventType).containsExactly(StockReserved.TYPE);
        StockReserved reserved = json.treeToValue(replies.getFirst().data(), StockReserved.class);
        assertThat(reserved.lines()).containsExactly(new StockReserved.Line(sku.value(), 3));
    }

    @Test
    void insufficientStockIsAnnouncedAsAFailureAndHoldsNothing() throws Exception {
        Sku sku = productWithStock(products, stock, 2);
        UUID orderId = UUID.randomUUID();

        publishOrderPlaced(UUID.randomUUID(), orderId, sku, 5);

        List<EventEnvelope> replies = repliesFor(orderId, 1);
        assertThat(replies).extracting(EventEnvelope::eventType).containsExactly(StockReservationFailed.TYPE);
        StockReservationFailed failed = json.treeToValue(replies.getFirst().data(), StockReservationFailed.class);
        assertThat(failed.reason()).contains("Insufficient stock");
        assertThat(stock.get(sku).reserved()).isZero();
        assertThat(reservationExists(orderId)).isFalse();
    }

    @Test
    void aRedeliveredOrderPlacedEventReservesOnlyOnce() throws Exception {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publishOrderPlaced(eventId, orderId, sku, 2);
        publishOrderPlaced(eventId, orderId, sku, 2); // same event delivered again

        List<EventEnvelope> replies = repliesFor(orderId, 1);
        assertThat(replies).hasSize(1);
        assertThat(stock.get(sku).reserved()).isEqualTo(2);
    }

    @Test
    void orderCancelledReleasesTheReservation() throws Exception {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(UUID.randomUUID(), orderId, sku, 4);
        awaitUntil(() -> stock.get(sku).reserved() == 4);

        publish(OrderPlaced.TOPIC, orderId, OrderCancelled.TYPE, new OrderCancelled(orderId, "customer request"));

        awaitUntil(() -> stock.get(sku).reserved() == 0);
        assertThat(reservations.get(orderId).status()).isEqualTo(Reservation.Status.RELEASED);
        assertThat(stock.get(sku).available()).isEqualTo(10);
    }

    @Test
    void shipmentDispatchedCommitsTheReservation() throws Exception {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        publishOrderPlaced(UUID.randomUUID(), orderId, sku, 4);
        awaitUntil(() -> stock.get(sku).reserved() == 4);

        publish(ShipmentDispatched.TOPIC, orderId, ShipmentDispatched.TYPE,
                new ShipmentDispatched(UUID.randomUUID(), orderId, "TRK-1"));

        awaitUntil(() -> stock.get(sku).onHand() == 6);
        assertThat(stock.get(sku).reserved()).isZero();
        assertThat(reservations.get(orderId).status()).isEqualTo(Reservation.Status.COMMITTED);
    }

    // --- helpers -----------------------------------------------------------------------------

    private void publishOrderPlaced(UUID eventId, UUID orderId, Sku sku, int quantity) throws Exception {
        var event = new OrderPlaced(orderId, UUID.randomUUID(),
                List.of(new OrderPlaced.Line(sku.value(), quantity, new BigDecimal("10.00"))),
                new BigDecimal("10.00").multiply(BigDecimal.valueOf(quantity)), "EUR", "Somewhere 1");
        publish(OrderPlaced.TOPIC, orderId, OrderPlaced.TYPE, event, eventId);
    }

    private void publish(String topic, UUID orderId, String type, Object data) throws Exception {
        publish(topic, orderId, type, data, UUID.randomUUID());
    }

    private void publish(String topic, UUID orderId, String type, Object data, UUID eventId) throws Exception {
        var envelope = new EventEnvelope(eventId, type, 1, Instant.now(), "saga-it", json.valueToTree(data));
        kafka.send(topic, orderId.toString(), json.writeValueAsString(envelope)).get();
    }

    private boolean reservationExists(UUID orderId) {
        try {
            reservations.get(orderId);
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /** Catalog's replies for one order; keeps polling briefly after {@code expected} to catch duplicates. */
    private List<EventEnvelope> repliesFor(UUID orderId, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "saga-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<EventEnvelope> replies = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(StockReserved.TOPIC));
            Instant deadline = Instant.now().plusSeconds(20);
            Instant settleUntil = null;
            while (Instant.now().isBefore(deadline) && (settleUntil == null || Instant.now().isBefore(settleUntil))) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (orderId.toString().equals(record.key())) {
                        replies.add(json.readValue(record.value(), EventEnvelope.class));
                    }
                }
                if (replies.size() >= expected && settleUntil == null) {
                    settleUntil = Instant.now().plusSeconds(2);
                }
            }
        }
        return replies;
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(20);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within 20s");
            }
            Thread.sleep(100);
        }
    }
}
