package io.github.ffetih.ecommerce.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RefundTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private static Payment captured() {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), Money.of(new BigDecimal("100.00"), "EUR"), "tok_ok", NOW)
                .authorized("AUTH-1", NOW).requestCapture(NOW).captured(NOW);
    }

    @Test
    void aRefundEndsOnceAndItsKeyNamesThePaymentAndTheReturn() {
        Payment payment = captured();
        UUID returnId = UUID.randomUUID();
        Refund started = Refund.start(payment, returnId, Money.of(new BigDecimal("30.00"), "EUR"), NOW);

        assertThat(started.inFlight()).isTrue();
        assertThat(started.idempotencyKey()).isEqualTo(payment.id() + ":refund:" + returnId);

        Refund refunded = started.refunded("REF-1", NOW);
        assertThat(refunded.refunded("REF-1", NOW)).isSameAs(refunded);
        assertThatIllegalStateException().isThrownBy(() -> refunded.failed("late", NOW));
        assertThat(started.failed("declined", NOW).failed("again", NOW).failureReason()).isEqualTo("declined");
    }

    @Test
    void aRefundIsInThePaymentsCurrency() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                Refund.start(captured(), UUID.randomUUID(), Money.of(new BigDecimal("30.00"), "TRY"), NOW));
    }
}
