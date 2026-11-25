package io.github.ffetih.ecommerce.e2e;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer cancels a confirmed order at the same moment the warehouse dispatches it, through the
 * gateway, against all four services. Before s06 this could split the system: the order cancelled and
 * its stock released while the parcel was on its way, and the dispatch event dead-lettered.
 * <p>
 * Now shipping decides (ADR-0007), and each round must end in exactly one of two consistent states:
 * <ul>
 *   <li>cancelled: order CANCELLED, shipment CANCELLED, stock back to what it was;</li>
 *   <li>shipped: order SHIPPED, shipment DISPATCHED, stock committed (on hand reduced, nothing reserved).</li>
 * </ul>
 * And no event of these orders may end in a dead-letter topic.
 */
class CancellationRaceE2EIT {

    private static final int ROUNDS = 8;
    private static final int STOCK = 5;
    private static final int ORDERED = 2;
    private static final List<String> DEAD_LETTER_TOPICS =
            List.of("order.lifecycle-dlt", "catalog.reservations-dlt", "shipping.shipments-dlt");

    private static Platform platform;
    private static Api catalog;
    private static Api customers;
    private static Api orders;
    private static Api shipping;

    @BeforeAll
    static void startPlatform() throws Exception {
        platform = Platform.shared();
        String admin = platform.tokens.user("admin");
        String alice = platform.tokens.user("alice");
        catalog = new Api(platform.gateway.baseUrl(), admin);
        customers = new Api(platform.gateway.baseUrl(), alice);
        orders = new Api(platform.gateway.baseUrl(), alice);
        shipping = new Api(platform.gateway.baseUrl(), admin);
    }

    @Test
    void cancellingAndDispatchingAtTheSameMomentNeverSplitsTheSystem() throws Exception {
        Shop.registered(customers, "Alice Archer");
        List<String> raced = new ArrayList<>();
        int cancelled = 0;
        int shipped = 0;

        for (int round = 0; round < ROUNDS; round++) {
            String sku = Shop.productWithStock(catalog, STOCK);
            String order = Shop.placeOrder(orders, sku, ORDERED).text("id");
            Shop.awaitOrderStatus(orders, order, "CONFIRMED");
            String shipment = Shop.awaitValue(() -> shipping.get("/api/v1/shipments?orderId=" + order),
                    response -> response.status() == 200).text("id");
            raced.add(order);

            race(order, shipment);

            String outcome = Shop.awaitValue(() -> orders.get("/api/v1/orders/" + order),
                    response -> Set.of("CANCELLED", "SHIPPED").contains(response.text("status"))).text("status");
            String shipmentStatus = shipping.get("/api/v1/shipments/" + shipment).text("status");
            if (outcome.equals("CANCELLED")) {
                assertThat(shipmentStatus).as("round %d, cancelled", round).isEqualTo("CANCELLED");
                Shop.awaitUntil(() -> Shop.stock(catalog, sku, "onHand") == STOCK && Shop.stock(catalog, sku, "reserved") == 0);
                cancelled++;
            } else {
                assertThat(shipmentStatus).as("round %d, shipped", round).isEqualTo("DISPATCHED");
                Shop.awaitUntil(() -> Shop.stock(catalog, sku, "onHand") == STOCK - ORDERED
                        && Shop.stock(catalog, sku, "reserved") == 0);
                shipped++;
            }
        }

        assertThat(cancelled + shipped).isEqualTo(ROUNDS);
        assertThat(deadLettered(raced)).as("dead-lettered events of the raced orders").isEmpty();
    }

    /**
     * Fires the customer's cancellation and the warehouse's dispatch at the same moment. Both answers
     * are checked on the test thread: an assertion failing inside an executor thread would otherwise
     * be kept in its future and never fail the test.
     */
    private static void race(String order, String shipment) throws Exception {
        var start = new CountDownLatch(1);
        Future<Integer> cancel;
        Future<Integer> dispatch;
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            cancel = threads.submit(() -> {
                start.await();
                return orders.post("/api/v1/orders/" + order + "/cancel", "{\"reason\":\"changed my mind\"}").status();
            });
            dispatch = threads.submit(() -> {
                start.await();
                return shipping.post("/api/v1/shipments/" + shipment + "/dispatch", null).status();
            });
            start.countDown();
        }
        // Cancel: 202 asked, or 409 the order was already shipped.
        assertThat(cancel.get()).as("cancel").isIn(202, 409);
        // Dispatch: 200 dispatched, or 409 the shipment was cancelled or changed at the same moment.
        assertThat(dispatch.get()).as("dispatch").isIn(200, 409);
    }

    /** Order ids found as keys in the dead-letter topics, read from the beginning. */
    private static Set<String> deadLettered(List<String> orderIds) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, platform.kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "e2e-dlt-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Set<String> found = new HashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(DEAD_LETTER_TOPICS);
            // Long enough for the group to get its partitions and for retries (a few seconds) to finish.
            Instant until = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(until)) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (orderIds.contains(record.key())) {
                        found.add(record.key());
                    }
                }
            }
        }
        return found;
    }
}
