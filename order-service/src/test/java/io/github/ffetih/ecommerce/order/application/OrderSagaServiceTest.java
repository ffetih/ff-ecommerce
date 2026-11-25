package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.IllegalOrderTransitionException;
import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
import io.github.ffetih.ecommerce.order.domain.Sku;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSagaServiceTest {

    private final OrderServiceTest.FakeOrders orders = new OrderServiceTest.FakeOrders();
    private final List<String> published = new ArrayList<>();
    private final Set<String> processed = new HashSet<>();

    private final OrderEvents events = new OrderEvents() {
        @Override
        public void orderPlaced(Order order) {
            published.add("placed");
        }

        @Override
        public void orderConfirmed(Order order) {
            published.add("confirmed");
        }

        @Override
        public void orderCancelled(Order order) {
            published.add("cancelled: " + order.cancellationReason());
        }
    };
    private final ProcessedEvents inbox = (consumer, eventId) -> processed.add(consumer + "/" + eventId);
    private final Transactions inline = new Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    private final OrderSagaService saga = new OrderSagaService(orders, events, inbox, inline,
            Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));

    private UUID placedOrder() {
        Order order = Order.place(UUID.randomUUID(),
                List.of(new OrderLine(Sku.of("KB-01"), 1, Money.of("10.00", "EUR"))), "Somewhere 1", Instant.now());
        orders.insert(order, UUID.randomUUID(), "hash");
        return order.id();
    }

    private Order.Status statusOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    @Test
    void stockReservedConfirmsTheOrderOnceEvenIfDeliveredTwice() {
        UUID orderId = placedOrder();
        UUID eventId = UUID.randomUUID();

        saga.stockReserved(eventId, orderId);
        saga.stockReserved(eventId, orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CONFIRMED);
        assertThat(published).containsExactly("confirmed");
    }

    @Test
    void failedReservationCancelsTheOrderWithTheReason() {
        UUID orderId = placedOrder();

        saga.reservationFailed(UUID.randomUUID(), orderId, "Insufficient stock for KB-01");

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
        assertThat(published).containsExactly("cancelled: Stock unavailable: Insufficient stock for KB-01");
    }

    @Test
    void aReservationArrivingAfterTheCustomerCancelledIsCompensated() {
        UUID orderId = placedOrder();
        saga.cancelByCustomer(orderId, "changed my mind");

        saga.stockReserved(UUID.randomUUID(), orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
        assertThat(published).containsExactly("cancelled: changed my mind", "cancelled: changed my mind");
    }

    @Test
    void shipmentEventsMoveTheOrderForwardAndBlockCancellation() {
        UUID orderId = placedOrder();
        saga.stockReserved(UUID.randomUUID(), orderId);

        saga.shipmentDispatched(UUID.randomUUID(), orderId);
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.SHIPPED);
        assertThatThrownBy(() -> saga.cancelByCustomer(orderId, "too late"))
                .isInstanceOf(IllegalOrderTransitionException.class);

        saga.shipmentDelivered(UUID.randomUUID(), orderId);
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.DELIVERED);
    }

    @Test
    void cancellingTwiceAnnouncesItOnce() {
        UUID orderId = placedOrder();

        saga.cancelByCustomer(orderId, "changed my mind");
        saga.cancelByCustomer(orderId, "changed my mind");

        assertThat(published).hasSize(1);
    }
}
