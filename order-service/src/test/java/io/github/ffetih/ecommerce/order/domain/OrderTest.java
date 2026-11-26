package io.github.ffetih.ecommerce.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final UUID CUSTOMER = UUID.randomUUID();

    private static OrderLine line(String sku, int quantity, String price) {
        return new OrderLine(Sku.of(sku), quantity, Money.of(price, "EUR"));
    }

    private static Order placed() {
        return Order.place(CUSTOMER, List.of(line("KB-01", 2, "89.90"), line("MOUSE-01", 1, "24.50")),
                "Bağdat Cd. 1, İstanbul", "tok_ok", NOW);
    }

    private static Order confirmed() {
        return placed().stockReserved(NOW).paymentAuthorized(NOW);
    }

    private static Order in(Order.Status status) {
        return switch (status) {
            case PLACED -> placed();
            case CONFIRMED -> confirmed();
            case CANCELLATION_REQUESTED -> confirmed().requestCancellation("changed my mind", NOW);
            case SHIPPED -> confirmed().ship(NOW);
            case DELIVERED -> confirmed().ship(NOW).deliver(NOW);
            case CANCELLED -> placed().cancel("customer request", NOW);
        };
    }

    @Test
    void computesTheTotalFromLinePricesInsteadOfTrustingTheClient() {
        assertThat(placed().total()).isEqualTo(Money.of("204.30", "EUR"));
        assertThat(placed().status()).isEqualTo(Order.Status.PLACED);
    }

    @Test
    void rejectsMixedCurrenciesDuplicateSkusAndEmptyOrders() {
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(CUSTOMER,
                List.of(line("KB-01", 1, "10.00"), new OrderLine(Sku.of("MOUSE-01"), 1, Money.of("5.00", "USD"))),
                "Somewhere", "tok_ok", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(CUSTOMER,
                List.of(line("KB-01", 1, "10.00"), line("kb-01", 2, "10.00")), "Somewhere", "tok_ok", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(CUSTOMER, List.of(), "Somewhere", "tok_ok", NOW));
    }

    @Test
    void rejectsAShippingAddressLongerThanTheStoredColumn() {
        String fiveHundred = "x".repeat(Order.MAX_ADDRESS_LENGTH);

        assertThat(Order.place(CUSTOMER, List.of(line("KB-01", 1, "10.00")), fiveHundred, "tok_ok", NOW).shippingAddress())
                .hasSize(Order.MAX_ADDRESS_LENGTH);
        assertThatIllegalArgumentException().isThrownBy(() ->
                Order.place(CUSTOMER, List.of(line("KB-01", 1, "10.00")), fiveHundred + "x", "tok_ok", NOW));
    }

    @Test
    void followsTheHappyPath() {
        Order delivered = confirmed().ship(NOW).deliver(NOW);

        assertThat(delivered.status()).isEqualTo(Order.Status.DELIVERED);
    }

    @Test
    void refusesToDeliverAnOrderThatWasNeverShipped() {
        // Delivery never overtakes dispatch on the topic; if it seems to, the dispatch was dead-lettered.
        assertIllegal(Order.Status.CONFIRMED, order -> order.deliver(NOW));
    }

    @ParameterizedTest
    @EnumSource(Order.Status.class)
    void repeatingAStepTheOrderHasAlreadyPassedChangesNothing(Order.Status status) {
        Order order = in(status);

        if (status != Order.Status.PLACED) {
            // Includes CANCELLED: a late "stock reserved" or authorization must not revive a cancelled order.
            assertThat(order.stockReserved(NOW.plusSeconds(60))).as("late reservation").isSameAs(order);
            assertThat(order.paymentAuthorized(NOW.plusSeconds(60))).as("late authorization").isSameAs(order);
        }
        if (status == Order.Status.SHIPPED || status == Order.Status.DELIVERED) {
            assertThat(order.ship(NOW.plusSeconds(60))).as("late ship").isSameAs(order);
        }
        if (status == Order.Status.DELIVERED) {
            assertThat(order.deliver(NOW.plusSeconds(60))).as("repeated deliver").isSameAs(order);
        }
        if (status == Order.Status.CANCELLATION_REQUESTED) {
            assertThat(order.requestCancellation("again", NOW.plusSeconds(60))).as("repeated request").isSameAs(order);
        }
        if (status == Order.Status.CANCELLED) {
            assertThat(order.cancel("again", NOW.plusSeconds(60))).as("repeated cancel").isSameAs(order);
            assertThat(order.requestCancellation("again", NOW.plusSeconds(60))).as("late request").isSameAs(order);
            assertThat(order.cancellationAccepted(NOW.plusSeconds(60))).as("repeated acceptance").isSameAs(order);
        }
        if (status == Order.Status.SHIPPED || status == Order.Status.DELIVERED || status == Order.Status.CONFIRMED) {
            assertThat(order.cancellationRefused(NOW.plusSeconds(60))).as("refusal after the fact").isSameAs(order);
        }
    }

    @Test
    void refusesImpossibleSteps() {
        assertIllegal(Order.Status.PLACED, order -> order.ship(NOW));
        assertIllegal(Order.Status.PLACED, order -> order.deliver(NOW));
        assertIllegal(Order.Status.CANCELLED, order -> order.ship(NOW));
        assertIllegal(Order.Status.CANCELLED, order -> order.deliver(NOW));
        assertIllegal(Order.Status.SHIPPED, order -> order.cancel("too late", NOW));
        assertIllegal(Order.Status.DELIVERED, order -> order.cancel("too late", NOW));
        // A confirmed order has a shipment: only shipping can stop it, so it is a request.
        assertIllegal(Order.Status.CONFIRMED, order -> order.cancel("directly", NOW));
        assertIllegal(Order.Status.PLACED, order -> order.requestCancellation("too early", NOW));
        assertIllegal(Order.Status.SHIPPED, order -> order.requestCancellation("too late", NOW));
        assertIllegal(Order.Status.CONFIRMED, order -> order.cancellationAccepted(NOW));
        assertIllegal(Order.Status.PLACED, order -> order.cancellationRefused(NOW));
        assertIllegal(Order.Status.CANCELLED, order -> order.ship(NOW));
    }

    @Test
    void cancellationNeedsAReasonAndKeepsIt() {
        assertThatIllegalArgumentException().isThrownBy(() -> placed().cancel(" ", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> in(Order.Status.CONFIRMED).requestCancellation(" ", NOW));
        assertThat(placed().cancel(" out of stock ", NOW).cancellationReason()).isEqualTo("out of stock");
    }

    @Test
    void anAcceptedRequestCancelsWithTheCustomersReason() {
        Order cancelled = in(Order.Status.CONFIRMED).requestCancellation(" changed my mind ", NOW).cancellationAccepted(NOW);

        assertThat(cancelled.status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(cancelled.cancellationReason()).isEqualTo("changed my mind");
    }

    @Test
    void aRefusedRequestLeavesTheOrderConfirmed() {
        Order refused = in(Order.Status.CANCELLATION_REQUESTED).cancellationRefused(NOW);

        assertThat(refused.status()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(refused.cancellationReason()).isNull();
    }

    @Test
    void aDispatchDuringThePendingRequestShipsTheOrderAndTheLateRefusalChangesNothing() {
        Order shipped = in(Order.Status.CANCELLATION_REQUESTED).ship(NOW);

        assertThat(shipped.status()).isEqualTo(Order.Status.SHIPPED);
        assertThat(shipped.cancellationRefused(NOW)).isSameAs(shipped);
    }

    // --- payment (ADR-0009) ----------------------------------------------------------------------

    @Test
    void needsAPaymentMethod() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                Order.place(CUSTOMER, List.of(line("KB-01", 1, "10.00")), "Somewhere", " ", NOW));
        assertThat(placed().cardToken()).isEqualTo("tok_ok");
        assertThat(placed().payment()).isEqualTo(Order.PaymentStatus.NOT_REQUESTED);
    }

    @Test
    void reservedStockAsksForThePaymentAndOnlyTheAuthorizationConfirms() {
        Order reserved = placed().stockReserved(NOW);

        assertThat(reserved.status()).isEqualTo(Order.Status.PLACED);
        assertThat(reserved.payment()).isEqualTo(Order.PaymentStatus.AUTHORIZING);
        assertThat(reserved.stockReserved(NOW)).as("repeated reservation").isSameAs(reserved);

        Order confirmed = reserved.paymentAuthorized(NOW);
        assertThat(confirmed.status()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(confirmed.payment()).isEqualTo(Order.PaymentStatus.AUTHORIZED);
        assertThat(confirmed.paymentAuthorized(NOW)).as("repeated authorization").isSameAs(confirmed);
    }

    @Test
    void anAuthorizationNobodyAskedForIsImpossible() {
        assertThatThrownBy(() -> placed().paymentAuthorized(NOW)).isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void aDeclinedPaymentCancelsTheOrder() {
        Order declined = placed().stockReserved(NOW).paymentDeclined("Card declined", NOW);

        assertThat(declined.status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(declined.cancellationReason()).isEqualTo("Payment declined: Card declined");
        assertThat(declined.payment()).isEqualTo(Order.PaymentStatus.DECLINED);
        assertThat(declined.paymentDeclined("Card declined", NOW)).isSameAs(declined);
        assertThatThrownBy(() -> confirmed().paymentDeclined("late", NOW)).isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void cancellingWhileTheAuthorizationIsPendingVoidsIt() {
        Order cancelled = placed().stockReserved(NOW).cancel("changed my mind", NOW);

        assertThat(cancelled.payment()).isEqualTo(Order.PaymentStatus.VOIDING);
        assertThat(cancelled.paymentAuthorized(NOW)).as("an approval after the cancellation").isSameAs(cancelled);
        assertThat(cancelled.paymentVoided(NOW).payment()).isEqualTo(Order.PaymentStatus.VOIDED);
        assertThat(cancelled.paymentDeclined("Card declined", NOW).payment())
                .as("declined after all: nothing to void").isEqualTo(Order.PaymentStatus.DECLINED);
    }

    @Test
    void cancellingBeforeAnyPaymentWasAskedForSendsNothing() {
        assertThat(placed().cancel("out of stock", NOW).payment()).isEqualTo(Order.PaymentStatus.NOT_REQUESTED);
    }

    @Test
    void anAcceptedCancellationVoidsTheHold() {
        Order cancelled = in(Order.Status.CANCELLATION_REQUESTED).cancellationAccepted(NOW);

        assertThat(cancelled.payment()).isEqualTo(Order.PaymentStatus.VOIDING);
    }

    @Test
    void theDispatchCapturesTheMoney() {
        Order shipped = confirmed().ship(NOW);

        assertThat(shipped.payment()).isEqualTo(Order.PaymentStatus.CAPTURING);
        Order captured = shipped.paymentCaptured(NOW);
        assertThat(captured.payment()).isEqualTo(Order.PaymentStatus.CAPTURED);
        assertThat(captured.paymentCaptured(NOW)).isSameAs(captured);
        assertThat(captured.deliver(NOW).payment()).isEqualTo(Order.PaymentStatus.CAPTURED);
    }

    @Test
    void aFailedCaptureLeavesTheOrderShippedWithTheMoneyOwed() {
        Order overdue = confirmed().ship(NOW).paymentCaptureFailed(NOW);

        assertThat(overdue.status()).isEqualTo(Order.Status.SHIPPED);
        assertThat(overdue.payment()).isEqualTo(Order.PaymentStatus.OVERDUE);
        assertThat(overdue.paymentCaptureFailed(NOW)).isSameAs(overdue);
        assertThatThrownBy(() -> confirmed().paymentCaptured(NOW)).isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void anOrderFromBeforePaymentsShipsWithoutOne() {
        Order legacy = new Order(UUID.randomUUID(), CUSTOMER, List.of(line("KB-01", 1, "10.00")), Money.of("10.00", "EUR"),
                "Somewhere", Order.Status.CONFIRMED, null, null, Order.PaymentStatus.NOT_REQUESTED, null, NOW, NOW);

        assertThat(legacy.ship(NOW).payment()).isEqualTo(Order.PaymentStatus.NOT_REQUESTED);
    }

    private static void assertIllegal(Order.Status from, UnaryOperator<Order> step) {
        assertThatThrownBy(() -> step.apply(in(from)))
                .as("from " + from)
                .isInstanceOf(IllegalOrderTransitionException.class);
    }
}
