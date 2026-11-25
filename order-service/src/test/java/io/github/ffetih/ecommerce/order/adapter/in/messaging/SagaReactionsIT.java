package io.github.ffetih.ecommerce.order.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.catalog.StockReservationFailed;
import io.github.ffetih.ecommerce.events.catalog.StockReserved;
import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.order.OrderConfirmed;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDelivered;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import io.github.ffetih.ecommerce.order.application.OrderService;
import io.github.ffetih.ecommerce.order.application.Requester;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
import io.github.ffetih.ecommerce.order.domain.Sku;
import io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Order-service in the saga, driven through Kafka: the test plays catalog and shipping. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SagaReactionsIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    Transactions transactions;

    @Autowired
    OrderService orders;

    @Autowired
    OrderSagaService saga;

    @Test
    void reservedStockConfirmsTheOrderAndAnnouncesIt() throws Exception {
        UUID orderId = placedOrder();

        publish(StockReserved.TOPIC, orderId, StockReserved.TYPE,
                new StockReserved(orderId, List.of(new StockReserved.Line("KB-01", 2))));

        awaitStatus(orderId, Order.Status.CONFIRMED);
        assertThat(lifecycleEventTypes(orderId, 1)).containsExactly(OrderConfirmed.TYPE);
    }

    @Test
    void failedReservationCancelsTheOrderAndAnnouncesIt() throws Exception {
        UUID orderId = placedOrder();

        publish(StockReserved.TOPIC, orderId, StockReservationFailed.TYPE,
                new StockReservationFailed(orderId, "Insufficient stock for KB-01"));

        awaitStatus(orderId, Order.Status.CANCELLED);
        assertThat(orders.get(orderId, Requester.backOffice()).cancellationReason()).contains("Insufficient stock");
        assertThat(lifecycleEventTypes(orderId, 1)).containsExactly(OrderCancelled.TYPE);
    }

    @Test
    void aLateReservationForACancelledOrderIsCompensated() throws Exception {
        UUID orderId = placedOrder();
        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());

        publish(StockReserved.TOPIC, orderId, StockReserved.TYPE,
                new StockReserved(orderId, List.of(new StockReserved.Line("KB-01", 2))));

        // The first order-cancelled comes from the customer, the second asks catalog to release the late reservation.
        assertThat(lifecycleEventTypes(orderId, 2)).containsExactly(OrderCancelled.TYPE, OrderCancelled.TYPE);
        assertThat(orders.get(orderId, Requester.backOffice()).status()).isEqualTo(Order.Status.CANCELLED);
    }

    @Test
    void shipmentEventsShipAndDeliverTheOrder() throws Exception {
        UUID orderId = placedOrder();
        publish(StockReserved.TOPIC, orderId, StockReserved.TYPE,
                new StockReserved(orderId, List.of(new StockReserved.Line("KB-01", 2))));
        awaitStatus(orderId, Order.Status.CONFIRMED);
        UUID shipmentId = UUID.randomUUID();

        publish(ShipmentDispatched.TOPIC, orderId, ShipmentDispatched.TYPE,
                new ShipmentDispatched(shipmentId, orderId, "TRK-1"));
        awaitStatus(orderId, Order.Status.SHIPPED);

        publish(ShipmentDispatched.TOPIC, orderId, ShipmentDelivered.TYPE, new ShipmentDelivered(shipmentId, orderId));
        awaitStatus(orderId, Order.Status.DELIVERED);
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID placedOrder() {
        Order order = Order.place(UUID.randomUUID(),
                List.of(new OrderLine(Sku.of("KB-01"), 2, Money.of("89.90", "EUR"))), "Somewhere 1", Instant.now());
        transactions.inTransaction(() -> {
            orderRepository.insert(order, UUID.randomUUID(), "test");
            return null;
        });
        return order.id();
    }

    private void publish(String topic, UUID orderId, String type, Object data) throws Exception {
        var envelope = new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "saga-it", json.valueToTree(data));
        kafka.send(topic, orderId.toString(), json.writeValueAsString(envelope)).get();
    }

    private void awaitStatus(UUID orderId, Order.Status expected) throws InterruptedException {
        awaitUntil(() -> orders.get(orderId, Requester.backOffice()).status() == expected);
    }

    /** Event types order-service published for one order; keeps polling briefly to catch extras. */
    private List<String> lifecycleEventTypes(UUID orderId, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "saga-reactions-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> types = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(OrderPlaced.TOPIC));
            Instant deadline = Instant.now().plusSeconds(20);
            Instant settleUntil = null;
            while (Instant.now().isBefore(deadline) && (settleUntil == null || Instant.now().isBefore(settleUntil))) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (orderId.toString().equals(record.key())) {
                        types.add(json.readValue(record.value(), EventEnvelope.class).eventType());
                    }
                }
                if (types.size() >= expected && settleUntil == null) {
                    settleUntil = Instant.now().plusSeconds(2);
                }
            }
        }
        return types;
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
