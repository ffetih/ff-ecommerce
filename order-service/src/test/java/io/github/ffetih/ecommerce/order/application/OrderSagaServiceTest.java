package io.github.ffetih.ecommerce.order.application;

import io.github.ffetih.ecommerce.order.application.port.OrderEvents;
import io.github.ffetih.ecommerce.order.application.port.PaymentCommands;
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
        public void cancellationRequested(Order order) {
            published.add("cancellation requested: " + order.cancellationReason());
        }

        @Override
        public void orderCancelled(Order order) {
            published.add("cancelled: " + order.cancellationReason());
        }
    };
    private final PaymentCommands commands = new PaymentCommands() {
        @Override
        public void authorize(Order order) {
            published.add("authorize-payment");
        }

        @Override
        public void capture(Order order) {
            published.add("capture-payment");
        }

        @Override
        public void voidPayment(Order order) {
            published.add("void-payment");
        }
    };
    private final ProcessedEvents inbox = (consumer, eventId) -> processed.add(consumer + "/" + eventId);
    private final Transactions inline = new Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    private final OrderSagaService saga = new OrderSagaService(orders, events, new PaymentSteps(commands), inbox, inline,
            Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC));

    private UUID placedOrder() {
        Order order = Order.place(UUID.randomUUID(),
                List.of(new OrderLine(Sku.of("KB-01"), 1, Money.of("10.00", "EUR"))), "Somewhere 1", "tok_ok", Instant.now());
        orders.insert(order, UUID.randomUUID(), "hash");
        return order.id();
    }

    /** Stock reserved and payment authorized; what was sent on the way is forgotten. */
    private UUID confirmedOrder() {
        UUID orderId = placedOrder();
        saga.stockReserved(UUID.randomUUID(), orderId);
        saga.paymentAuthorized(UUID.randomUUID(), orderId);
        published.clear();
        return orderId;
    }

    private Order.Status statusOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().status();
    }

    @Test
    void stockReservedAsksForThePaymentOnceEvenIfDeliveredTwice() {
        UUID orderId = placedOrder();
        UUID eventId = UUID.randomUUID();

        saga.stockReserved(eventId, orderId);
        saga.stockReserved(eventId, orderId);
        saga.stockReserved(UUID.randomUUID(), orderId); // a second reservation event, not a redelivery

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.PLACED);
        assertThat(published).containsExactly("authorize-payment");
    }

    @Test
    void theAuthorizationConfirmsTheOrder() {
        UUID orderId = placedOrder();
        saga.stockReserved(UUID.randomUUID(), orderId);
        UUID eventId = UUID.randomUUID();

        saga.paymentAuthorized(eventId, orderId);
        saga.paymentAuthorized(eventId, orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CONFIRMED);
        assertThat(published).containsExactly("authorize-payment", "confirmed");
    }

    @Test
    void aDeclinedPaymentCancelsTheOrderAndReleasesTheStock() {
        UUID orderId = placedOrder();
        saga.stockReserved(UUID.randomUUID(), orderId);

        saga.paymentDeclined(UUID.randomUUID(), orderId, "Card declined");

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
        assertThat(published).containsExactly("authorize-payment", "cancelled: Payment declined: Card declined");
    }

    @Test
    void cancellingWhileTheAuthorizationIsPendingVoidsItAndALateApprovalChangesNothing() {
        UUID orderId = placedOrder();
        saga.stockReserved(UUID.randomUUID(), orderId);

        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());
        saga.paymentAuthorized(UUID.randomUUID(), orderId);
        saga.paymentVoided(UUID.randomUUID(), orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
        assertThat(orders.findById(orderId).orElseThrow().payment()).isEqualTo(Order.PaymentStatus.VOIDED);
        assertThat(published).containsExactly("authorize-payment", "void-payment", "cancelled: changed my mind");
    }

    @Test
    void aDeclineAfterTheCustomerCancelledIsNotAnnouncedAgain() {
        UUID orderId = placedOrder();
        saga.stockReserved(UUID.randomUUID(), orderId);
        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());
        published.clear();

        saga.paymentDeclined(UUID.randomUUID(), orderId, "Card declined");

        assertThat(published).isEmpty();
        assertThat(orders.findById(orderId).orElseThrow().cancellationReason()).isEqualTo("changed my mind");
    }

    @Test
    void theDispatchCapturesAndAFailedCaptureDoesNotUndoTheShipment() {
        UUID orderId = confirmedOrder();

        saga.shipmentDispatched(UUID.randomUUID(), orderId);
        saga.paymentCaptureFailed(UUID.randomUUID(), orderId, "Authorization expired");

        assertThat(published).containsExactly("capture-payment");
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.SHIPPED);
        assertThat(orders.findById(orderId).orElseThrow().payment()).isEqualTo(Order.PaymentStatus.OVERDUE);
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
        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());

        saga.stockReserved(UUID.randomUUID(), orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
        assertThat(published).containsExactly("cancelled: changed my mind", "cancelled: changed my mind");
    }

    @Test
    void shipmentEventsMoveTheOrderForwardAndBlockCancellation() {
        UUID orderId = confirmedOrder();

        saga.shipmentDispatched(UUID.randomUUID(), orderId);
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.SHIPPED);
        assertThatThrownBy(() -> saga.cancelByCustomer(orderId, "too late", Requester.backOffice()))
                .isInstanceOf(IllegalOrderTransitionException.class);

        saga.shipmentDelivered(UUID.randomUUID(), orderId);
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.DELIVERED);
    }

    @Test
    void cancellingTwiceAnnouncesItOnce() {
        UUID orderId = placedOrder();

        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());
        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());

        assertThat(published).hasSize(1);
    }

    @Test
    void onlyTheOrdersCustomerOrTheBackOfficeMayCancel() {
        UUID orderId = placedOrder();
        UUID owner = orders.findById(orderId).orElseThrow().customerId();

        assertThatThrownBy(() -> saga.cancelByCustomer(orderId, "not mine", Requester.customer(UUID.randomUUID())))
                .isInstanceOf(OrderNotFoundException.class);
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.PLACED);
        assertThat(published).isEmpty();

        saga.cancelByCustomer(orderId, "changed my mind", Requester.customer(owner));
        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
    }

    @Test
    void cancellingAConfirmedOrderAsksShippingInsteadOfCancelling() {
        UUID orderId = confirmedOrder();

        Order requested = saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());

        assertThat(requested.status()).isEqualTo(Order.Status.CANCELLATION_REQUESTED);
        // Not "cancelled": catalog must not release the stock before shipping has stopped the parcel.
        assertThat(published).containsExactly("cancellation requested: changed my mind");
    }

    @Test
    void shippingAcceptingTheRequestCancelsTheOrderAndReleasesTheStockAndTheHold() {
        UUID orderId = confirmedOrder();
        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());
        published.clear();

        saga.shipmentCancelled(UUID.randomUUID(), orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.CANCELLED);
        assertThat(published).containsExactly("void-payment", "cancelled: changed my mind");
    }

    @Test
    void aDispatchDuringThePendingRequestWinsAndTheRefusalChangesNothing() {
        UUID orderId = confirmedOrder();
        saga.cancelByCustomer(orderId, "changed my mind", Requester.backOffice());
        published.clear();

        saga.shipmentDispatched(UUID.randomUUID(), orderId);
        saga.cancellationRefused(UUID.randomUUID(), orderId);

        assertThat(statusOf(orderId)).isEqualTo(Order.Status.SHIPPED);
        assertThat(published).as("nothing released; the money is taken").containsExactly("capture-payment");
    }
}
