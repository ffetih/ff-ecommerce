package io.github.ffetih.ecommerce.shipping.application;

import io.github.ffetih.ecommerce.events.shipping.CancellationRefused;
import io.github.ffetih.ecommerce.events.shipping.ShipmentCancelled;
import io.github.ffetih.ecommerce.events.shipping.ShipmentDispatched;
import io.github.ffetih.ecommerce.shipping.domain.IllegalShipmentTransitionException;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import io.github.ffetih.ecommerce.shipping.domain.ShipmentLine;
import io.github.ffetih.ecommerce.shipping.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The warehouse dispatches a shipment at the very moment the customer's cancellation request arrives.
 * Whatever the interleaving, shipping must end in one of two consistent outcomes, never both and
 * never neither:
 * <ul>
 *   <li>cancel won: shipment CANCELLED, only {@code shipment-cancelled} published, the dispatch failed;</li>
 *   <li>dispatch won: shipment DISPATCHED, {@code shipment-dispatched} then {@code cancellation-refused}.</li>
 * </ul>
 * The test calls the service directly from two threads and repeats a request that lost the version
 * check, as the Kafka error handler does.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CancellationRaceIT {

    private static final int ROUNDS = 20;

    @Autowired
    ShippingService shipping;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void dispatchAndCancellationRequestAtTheSameMomentEndConsistently() throws Exception {
        int cancelWon = 0;
        int dispatchWon = 0;
        for (int round = 0; round < ROUNDS; round++) {
            UUID orderId = UUID.randomUUID();
            shipping.orderConfirmed(UUID.randomUUID(), orderId, UUID.randomUUID(),
                    List.of(new ShipmentLine("KB-01", 1)), "Bağdat Cd. 1, İstanbul");
            UUID shipmentId = shipping.forOrder(orderId).id();

            race(shipmentId, orderId);

            Shipment.Status status = shipping.forOrder(orderId).status();
            List<String> published = publishedFor(orderId);
            if (status == Shipment.Status.CANCELLED) {
                assertThat(published).as("round %d, cancel won", round).containsExactly(ShipmentCancelled.TYPE);
                cancelWon++;
            } else {
                assertThat(status).as("round %d", round).isEqualTo(Shipment.Status.DISPATCHED);
                assertThat(published).as("round %d, dispatch won", round)
                        .containsExactly(ShipmentDispatched.TYPE, CancellationRefused.TYPE);
                dispatchWon++;
            }
        }
        assertThat(cancelWon + dispatchWon).isEqualTo(ROUNDS);
    }

    private void race(UUID shipmentId, UUID orderId) throws InterruptedException {
        var start = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            threads.submit(() -> {
                start.await();
                try {
                    shipping.dispatch(shipmentId);
                } catch (IllegalShipmentTransitionException | OptimisticLockingFailureException lost) {
                    // The warehouse gets a 409: the shipment was cancelled, or changed meanwhile.
                }
                return null;
            });
            threads.submit(() -> {
                start.await();
                UUID eventId = UUID.randomUUID();
                for (int attempt = 1; ; attempt++) {
                    try {
                        shipping.cancellationRequested(eventId, orderId);
                        return null;
                    } catch (OptimisticLockingFailureException lost) {
                        if (attempt == 5) {
                            throw lost;
                        }
                        // Redelivered by the Kafka error handler; the next attempt sees the dispatch.
                    }
                }
            });
            start.countDown();
        }
    }

    private List<String> publishedFor(UUID orderId) {
        return jdbc.queryForList("SELECT event_type FROM outbox_messages WHERE message_key = ? ORDER BY id",
                String.class, orderId.toString());
    }
}
