package io.github.ffetih.ecommerce.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Currency TRY = Currency.getInstance("TRY");

    private static Payment authorized() {
        return Payment.start(UUID.randomUUID(), UUID.randomUUID(), Money.of(new BigDecimal("250.00"), "TRY"), "tok_ok", NOW)
                .authorized("AUTH-1", NOW);
    }

    @Test
    void aCaptureMovesTheAmountFromSalesToTheProvider() {
        Payment captured = authorized().requestCapture(NOW).captured(NOW);

        LedgerTransaction booked = LedgerTransaction.capture(captured, NOW);

        assertThat(booked.kind()).isEqualTo(LedgerTransaction.Kind.CAPTURE);
        assertThat(booked.reference()).isEqualTo(captured.idempotencyKey(Payment.Operation.CAPTURE));
        assertThat(booked.currency()).isEqualTo(TRY);
        assertThat(booked.entries()).containsExactly(
                new LedgerEntry(Account.PROVIDER_RECEIVABLE, new BigDecimal("250.00")),
                new LedgerEntry(Account.SALES, new BigDecimal("-250.00")));
    }

    @Test
    void onlyACapturedPaymentIsBooked() {
        assertThatThrownBy(() -> LedgerTransaction.capture(authorized(), NOW))
                .isInstanceOf(IllegalPaymentTransitionException.class);
    }

    @Test
    void entriesMustBalance() {
        assertThatThrownBy(() -> new LedgerTransaction(UUID.randomUUID(), UUID.randomUUID(), "ref", LedgerTransaction.Kind.CAPTURE,
                TRY, List.of(new LedgerEntry(Account.PROVIDER_RECEIVABLE, BigDecimal.TEN),
                        new LedgerEntry(Account.SALES, new BigDecimal("-9.99"))), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to zero");
        assertThatThrownBy(() -> new LedgerEntry(Account.SALES, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
