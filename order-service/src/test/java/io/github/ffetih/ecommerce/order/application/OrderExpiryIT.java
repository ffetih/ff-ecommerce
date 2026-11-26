package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.events.order.OrderCancellationRequested;
import io.github.ffetih.ecommerce.events.order.OrderCancelled;
import io.github.ffetih.ecommerce.events.payment.VoidPayment;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
import io.github.ffetih.ecommerce.order.domain.Sku;
import io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overdue orders are stopped through the cancellation gate, against real PostgreSQL. The test passes
 * the time in instead of waiting; its overdue orders date from 2020, so orders other test classes
 * create in the shared database are never old enough to be touched.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderExpiryIT {

    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    OrderExpiry expiry;

    @Autowired
    OrderRepository orders;

    @Autowired
    Transactions transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aPlacedOrderThatWasNeverConfirmedIsCancelledAndAnnounced() {
        UUID order = stored(LONG_AGO, UnaryOperator.identity());

        expiry.expireStale(Instant.now());

        Order expired = load(order);
        assertThat(expired.status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(expired.cancellationReason()).isEqualTo(OrderExpiry.NOT_CONFIRMED_IN_TIME);
        assertThat(published(order)).containsExactly(OrderCancelled.TYPE);
    }

    @Test
    void aPlacedOrderWhosePaymentNeverGotAnAnswerIsCancelledAndItsAuthorizationVoided() {
        UUID order = stored(LONG_AGO, placed -> placed.stockReserved(LONG_AGO));

        expiry.expireStale(Instant.now());

        Order expired = load(order);
        assertThat(expired.status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(expired.payment()).isEqualTo(Order.PaymentStatus.VOIDING);
        assertThat(published(order)).containsExactly(VoidPayment.TYPE, OrderCancelled.TYPE);
    }

    @Test
    void aConfirmedOrderThatWasNeverDispatchedIsHandedToShippingToDecide() {
        UUID order = stored(LONG_AGO, placed -> placed.stockReserved(LONG_AGO).paymentAuthorized(LONG_AGO));

        expiry.expireStale(Instant.now());

        Order expired = load(order);
        assertThat(expired.status()).isEqualTo(Order.Status.CANCELLATION_REQUESTED);
        assertThat(expired.cancellationReason()).isEqualTo(OrderExpiry.NOT_DISPATCHED_IN_TIME);
        // A request, not a cancellation: catalog keeps the stock until shipping has stopped the parcel.
        assertThat(published(order)).containsExactly(OrderCancellationRequested.TYPE);
    }

    @Test
    void ordersStillWithinTheirTimeOrAlreadyBeyondTheGateAreLeftAlone() {
        Instant now = Instant.now();
        List<UUID> untouched = List.of(
                stored(now.minus(Duration.ofMinutes(10)), UnaryOperator.identity()),
                stored(now.minus(Duration.ofDays(1)), placed -> placed.stockReserved(now.minus(Duration.ofDays(1)))
                        .paymentAuthorized(now.minus(Duration.ofDays(1)))),
                stored(LONG_AGO, placed -> placed.stockReserved(LONG_AGO).paymentAuthorized(LONG_AGO)
                        .requestCancellation("asked earlier", LONG_AGO)),
                stored(LONG_AGO, placed -> placed.stockReserved(LONG_AGO).paymentAuthorized(LONG_AGO).ship(LONG_AGO)),
                stored(LONG_AGO, placed -> placed.stockReserved(LONG_AGO).paymentAuthorized(LONG_AGO).ship(LONG_AGO)
                        .deliver(LONG_AGO)),
                stored(LONG_AGO, placed -> placed.cancel("cancelled earlier", LONG_AGO)));
        List<Order.Status> before = untouched.stream().map(id -> load(id).status()).toList();

        expiry.expireStale(now);

        assertThat(untouched.stream().map(id -> load(id).status()).toList()).isEqualTo(before);
        untouched.forEach(id -> assertThat(published(id)).as("events for %s", id).isEmpty());
    }

    @Test
    void twoScannersAtOnceExpireEveryOrderExactlyOnce() throws Exception {
        List<UUID> overdue = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            overdue.add(stored(LONG_AGO, placed -> placed.stockReserved(LONG_AGO).paymentAuthorized(LONG_AGO)));
        }
        Instant now = Instant.now();
        var start = new CountDownLatch(1);

        try (var scanners = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int scanner = 0; scanner < 2; scanner++) {
                scanners.submit(() -> {
                    start.await();
                    return expiry.expireStale(now);
                });
            }
            start.countDown();
        }

        for (UUID order : overdue) {
            assertThat(load(order).status()).isEqualTo(Order.Status.CANCELLATION_REQUESTED);
            assertThat(published(order)).as("events for %s", order).containsExactly(OrderCancellationRequested.TYPE);
        }
    }

    /** Stores an order placed at {@code at}, after applying {@code steps} to it (all happening at that time). */
    private UUID stored(Instant at, UnaryOperator<Order> steps) {
        Order placed = Order.place(UUID.randomUUID(),
                List.of(new OrderLine(Sku.of("KB-01"), 1, Money.of("10.00", "EUR"))), "Somewhere 1", "tok_ok", at);
        Order order = steps.apply(placed);
        transactions.inTransaction(() -> {
            orders.insert(order, UUID.randomUUID(), "expiry-it");
            return null;
        });
        return order.id();
    }

    private Order load(UUID order) {
        return transactions.inTransaction(() -> orders.findById(order)).orElseThrow();
    }

    private List<String> published(UUID order) {
        return jdbc.queryForList("SELECT event_type FROM outbox_messages WHERE message_key = ? ORDER BY id",
                String.class, order.toString());
    }
}
