package io.github.ffetih.ecommerce.shipping.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.order.OrderCancellationRequested;
import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.order.OrderConfirmed;
import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.events.shipping.CancellationRefused;
import io.github.ffetih.ecommerce.events.shipping.ShipmentCancelled;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDelivered;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.shipping.application.ShipmentNotFoundException;
import io.github.ffetih.ecommerce.shipping.application.ShippingService;
import io.github.ffetih.ecommerce.shipping.domain.IllegalShipmentTransitionException;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import io.github.ffetih.ecommerce.shipping.support.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Shipping in the saga: the test plays order-service on Kafka and the warehouse through the service API. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShippingSagaIT {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JsonMapper json;

    @Autowired
    ShippingService shipping;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aConfirmedOrderGetsExactlyOneShipmentEvenIfTheEventIsRedelivered() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        publishOrderConfirmed(eventId, orderId);
        publishOrderConfirmed(eventId, orderId);

        awaitUntil(() -> shipmentExists(orderId));
        Thread.sleep(1_000); // give a duplicate the chance to show up
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipments WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(shipping.forOrder(orderId).status()).isEqualTo(Shipment.Status.CREATED);
    }

    @Test
    void dispatchAndDeliveryArePublishedForTheOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderConfirmed(UUID.randomUUID(), orderId);
        awaitUntil(() -> shipmentExists(orderId));
        UUID shipmentId = shipping.forOrder(orderId).id();

        Shipment dispatched = shipping.dispatch(shipmentId);
        shipping.dispatch(shipmentId); // a double click in the warehouse UI publishes nothing new
        shipping.deliver(shipmentId);

        assertThat(dispatched.trackingNumber()).startsWith("TRK-");
        assertThat(shipmentEventTypes(orderId, 2)).containsExactly(ShipmentDispatched.TYPE, ShipmentDelivered.TYPE);
    }

    @Test
    void cancellingTheOrderBeforeDispatchStopsTheShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderConfirmed(UUID.randomUUID(), orderId);
        awaitUntil(() -> shipmentExists(orderId));

        publish(orderId, OrderCancelled.TYPE, new OrderCancelled(orderId, "customer request"), UUID.randomUUID());

        awaitUntil(() -> shipping.forOrder(orderId).status() == Shipment.Status.CANCELLED);
        UUID shipmentId = shipping.forOrder(orderId).id();
        assertThatThrownBy(() -> shipping.dispatch(shipmentId)).isInstanceOf(IllegalShipmentTransitionException.class);
    }

    @Test
    void aCancellationAfterDispatchLeavesTheShipmentDispatched() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderConfirmed(UUID.randomUUID(), orderId);
        awaitUntil(() -> shipmentExists(orderId));
        shipping.dispatch(shipping.forOrder(orderId).id());
        UUID cancellation = UUID.randomUUID();

        publish(orderId, OrderCancelled.TYPE, new OrderCancelled(orderId, "too late"), cancellation);
        awaitUntil(() -> inboxContains(cancellation));

        assertThat(shipping.forOrder(orderId).status()).isEqualTo(Shipment.Status.DISPATCHED);
    }

    @Test
    void aCancellationRequestBeforeDispatchCancelsTheShipmentAndAcceptsTheRequest() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderConfirmed(UUID.randomUUID(), orderId);
        awaitUntil(() -> shipmentExists(orderId));

        publish(orderId, OrderCancellationRequested.TYPE, new OrderCancellationRequested(orderId, "changed my mind"),
                UUID.randomUUID());

        assertThat(shipmentEventTypes(orderId, 1)).containsExactly(ShipmentCancelled.TYPE);
        assertThat(shipping.forOrder(orderId).status()).isEqualTo(Shipment.Status.CANCELLED);
        UUID shipmentId = shipping.forOrder(orderId).id();
        assertThatThrownBy(() -> shipping.dispatch(shipmentId)).isInstanceOf(IllegalShipmentTransitionException.class);
    }

    @Test
    void aCancellationRequestAfterDispatchIsRefused() throws Exception {
        UUID orderId = UUID.randomUUID();
        publishOrderConfirmed(UUID.randomUUID(), orderId);
        awaitUntil(() -> shipmentExists(orderId));
        shipping.dispatch(shipping.forOrder(orderId).id());

        publish(orderId, OrderCancellationRequested.TYPE, new OrderCancellationRequested(orderId, "too late"),
                UUID.randomUUID());

        // The refusal follows the dispatch on the same partition, so order-service sees them in this order.
        assertThat(shipmentEventTypes(orderId, 2)).containsExactly(ShipmentDispatched.TYPE, CancellationRefused.TYPE);
        assertThat(shipping.forOrder(orderId).status()).isEqualTo(Shipment.Status.DISPATCHED);
    }

    @Test
    void aCancellationRequestForAnOrderWithoutShipmentIsAccepted() throws Exception {
        UUID orderId = UUID.randomUUID();

        publish(orderId, OrderCancellationRequested.TYPE, new OrderCancellationRequested(orderId, "never confirmed here"),
                UUID.randomUUID());

        assertThat(shipmentEventTypes(orderId, 1)).containsExactly(ShipmentCancelled.TYPE);
    }

    // --- helpers -----------------------------------------------------------------------------

    private void publishOrderConfirmed(UUID eventId, UUID orderId) throws Exception {
        publish(orderId, OrderConfirmed.TYPE, new OrderConfirmed(orderId, UUID.randomUUID(),
                List.of(new OrderConfirmed.Line("KB-01", 2)), "Bağdat Cd. 1, İstanbul"), eventId);
    }

    private void publish(UUID orderId, String type, Object data, UUID eventId) throws Exception {
        var envelope = new EventEnvelope(eventId, type, 1, Instant.now(), "shipping-it", json.valueToTree(data));
        kafka.send(OrderPlaced.TOPIC, orderId.toString(), json.writeValueAsString(envelope)).get();
    }

    private boolean shipmentExists(UUID orderId) {
        try {
            shipping.forOrder(orderId);
            return true;
        } catch (ShipmentNotFoundException e) {
            return false;
        }
    }

    private boolean inboxContains(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM inbox_messages WHERE event_id = ?", Integer.class, eventId) == 1;
    }

    private List<String> shipmentEventTypes(UUID orderId, int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "shipping-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> types = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(ShipmentDispatched.TOPIC));
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
