package io.github.ffetih.ecommerce.order.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnTest {

    private static final Instant DELIVERED = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant NOW = DELIVERED.plus(Duration.ofDays(3));
    private static final Duration WINDOW = Duration.ofDays(14);
    private static final Sku KEYBOARD = Sku.of("KB-01");
    private static final Sku MOUSE = Sku.of("MOUSE-01");

    private static Order placed() {
        return Order.place(UUID.randomUUID(),
                List.of(new OrderLine(KEYBOARD, 2, Money.of("89.90", "EUR")), new OrderLine(MOUSE, 1, Money.of("24.50", "EUR"))),
                "Bağdat Cd. 1, İstanbul", "tok_ok", DELIVERED);
    }

    private static Order delivered() {
        return placed().stockReserved(DELIVERED).paymentAuthorized(DELIVERED).ship(DELIVERED)
                .paymentCaptured(DELIVERED).deliver(DELIVERED);
    }

    private static Return requested() {
        return Return.request(delivered(), List.of(new Return.Requested(KEYBOARD, 1)), "too loud", WINDOW, NOW);
    }

    @Test
    void deliveryIsRecordedOnce() {
        Order delivered = delivered();

        assertThat(delivered.deliveredAt()).isEqualTo(DELIVERED);
        assertThat(delivered.deliver(NOW).deliveredAt()).as("repeated delivery").isEqualTo(DELIVERED);
    }

    @Test
    void refundsTheFrozenPricesOfTheReturnedUnits() {
        Return requested = Return.request(delivered(),
                List.of(new Return.Requested(KEYBOARD, 2), new Return.Requested(MOUSE, 1)), " too loud ", WINDOW, NOW);

        assertThat(requested.status()).isEqualTo(Return.Status.REQUESTED);
        assertThat(requested.refundAmount()).isEqualTo(Money.of("204.30", "EUR"));
        assertThat(requested.reason()).isEqualTo("too loud");
    }

    @Test
    void onlyDeliveredPaidOrdersWithinTheWindowCanBeReturned() {
        List<Return.Requested> one = List.of(new Return.Requested(KEYBOARD, 1));

        assertThatThrownBy(() -> Return.request(placed(), one, "why", WINDOW, NOW))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> Return.request(delivered(), one, "why", WINDOW, DELIVERED.plus(Duration.ofDays(15))))
                .isInstanceOf(ReturnNotAllowedException.class).hasMessageContaining("14 days");
        Order overdue = placed().stockReserved(DELIVERED).paymentAuthorized(DELIVERED).ship(DELIVERED)
                .paymentCaptureFailed(DELIVERED).deliver(DELIVERED);
        assertThatThrownBy(() -> Return.request(overdue, one, "why", WINDOW, NOW))
                .isInstanceOf(ReturnNotAllowedException.class).hasMessageContaining("OVERDUE");
    }

    @Test
    void linesMustBelongToTheOrderAndNotExceedIt() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                Return.request(delivered(), List.of(new Return.Requested(Sku.of("OTHER-01"), 1)), "why", WINDOW, NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> Return.request(delivered(),
                List.of(new Return.Requested(KEYBOARD, 1), new Return.Requested(KEYBOARD, 1)), "why", WINDOW, NOW));
        assertThatThrownBy(() -> Return.request(delivered(), List.of(new Return.Requested(KEYBOARD, 3)), "why", WINDOW, NOW))
                .isInstanceOf(ReturnNotAllowedException.class);
        assertThatIllegalArgumentException().isThrownBy(() ->
                Return.request(delivered(), List.of(new Return.Requested(KEYBOARD, 1)), " ", WINDOW, NOW));
    }

    @Test
    void receivingTwiceChangesNothingAndARejectedReturnCannotBeReceived() {
        Return received = requested().receive(NOW);

        assertThat(received.status()).isEqualTo(Return.Status.RECEIVED);
        assertThat(received.receive(NOW)).isSameAs(received);

        Return rejected = requested().reject("never arrived", NOW);
        assertThat(rejected.rejectionReason()).isEqualTo("never arrived");
        assertThat(rejected.reject("again", NOW)).isSameAs(rejected);
        assertThatThrownBy(() -> rejected.receive(NOW)).isInstanceOf(IllegalReturnTransitionException.class);
        assertThatThrownBy(() -> received.reject("too late", NOW)).isInstanceOf(IllegalReturnTransitionException.class);
    }

    @Test
    void theRefundFollowsTheReceipt() {
        assertThatThrownBy(() -> requested().refunded(NOW)).isInstanceOf(IllegalReturnTransitionException.class);

        Return refunded = requested().receive(NOW).refunded(NOW);
        assertThat(refunded.status()).isEqualTo(Return.Status.REFUNDED);
        assertThat(refunded.refunded(NOW)).isSameAs(refunded);
        assertThat(refunded.receive(NOW)).isSameAs(refunded);
        assertThat(requested().receive(NOW).refundFailed(NOW).status()).isEqualTo(Return.Status.REFUND_FAILED);
    }
}
