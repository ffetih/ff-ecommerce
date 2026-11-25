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
                "Bağdat Cd. 1, İstanbul", NOW);
    }

    private static Order in(Order.Status status) {
        return switch (status) {
            case PLACED -> placed();
            case CONFIRMED -> placed().confirm(NOW);
            case SHIPPED -> placed().confirm(NOW).ship(NOW);
            case DELIVERED -> placed().confirm(NOW).ship(NOW).deliver(NOW);
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
                "Somewhere", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(CUSTOMER,
                List.of(line("KB-01", 1, "10.00"), line("kb-01", 2, "10.00")), "Somewhere", NOW));
        assertThatIllegalArgumentException().isThrownBy(() -> Order.place(CUSTOMER, List.of(), "Somewhere", NOW));
    }

    @Test
    void rejectsAShippingAddressLongerThanTheStoredColumn() {
        String fiveHundred = "x".repeat(Order.MAX_ADDRESS_LENGTH);

        assertThat(Order.place(CUSTOMER, List.of(line("KB-01", 1, "10.00")), fiveHundred, NOW).shippingAddress())
                .hasSize(Order.MAX_ADDRESS_LENGTH);
        assertThatIllegalArgumentException().isThrownBy(() ->
                Order.place(CUSTOMER, List.of(line("KB-01", 1, "10.00")), fiveHundred + "x", NOW));
    }

    @Test
    void followsTheHappyPath() {
        Order delivered = placed().confirm(NOW).ship(NOW).deliver(NOW);

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
            // Includes CANCELLED: a late "stock reserved" must not revive a cancelled order.
            assertThat(order.confirm(NOW.plusSeconds(60))).as("late confirm").isSameAs(order);
        }
        if (status == Order.Status.SHIPPED || status == Order.Status.DELIVERED) {
            assertThat(order.ship(NOW.plusSeconds(60))).as("late ship").isSameAs(order);
        }
        if (status == Order.Status.DELIVERED) {
            assertThat(order.deliver(NOW.plusSeconds(60))).as("repeated deliver").isSameAs(order);
        }
        if (status == Order.Status.CANCELLED) {
            assertThat(order.cancel("again", NOW.plusSeconds(60))).as("repeated cancel").isSameAs(order);
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
    }

    @Test
    void cancellationNeedsAReasonAndKeepsIt() {
        assertThatIllegalArgumentException().isThrownBy(() -> placed().cancel(" ", NOW));
        assertThat(in(Order.Status.CONFIRMED).cancel(" out of stock ", NOW).cancellationReason()).isEqualTo("out of stock");
    }

    private static void assertIllegal(Order.Status from, UnaryOperator<Order> step) {
        assertThatThrownBy(() -> step.apply(in(from)))
                .as("from " + from)
                .isInstanceOf(IllegalOrderTransitionException.class);
    }
}
