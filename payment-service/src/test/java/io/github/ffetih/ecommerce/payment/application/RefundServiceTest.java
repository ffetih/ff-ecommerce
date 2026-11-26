package io.github.ffetih.ecommerce.payment.application;

import io.github.ffetih.ecommerce.payment.adapter.out.provider.SimulatedPaymentProvider;
import io.github.ffetih.ecommerce.payment.application.Fakes.CountingProvider;
import io.github.ffetih.ecommerce.payment.application.Fakes.InMemoryLedger;
import io.github.ffetih.ecommerce.payment.application.Fakes.InMemoryPayments;
import io.github.ffetih.ecommerce.payment.application.Fakes.InMemoryRefunds;
import io.github.ffetih.ecommerce.payment.application.Fakes.RecordedEvents;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Refund;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The refund rules, with in-memory ports and the simulated provider (ADR-0010). */
class RefundServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Money PAID = Money.of(new BigDecimal("100.00"), "EUR");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final InMemoryRefunds refunds = new InMemoryRefunds();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final RecordedEvents events = new RecordedEvents();
    private final CountingProvider provider = new CountingProvider(new SimulatedPaymentProvider());
    private final Set<UUID> inbox = new HashSet<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PaymentService.Settings settings = new PaymentService.Settings(Duration.ZERO, 10);
    private final PaymentService paymentService = new PaymentService(payments, ledger, provider, events,
            (consumer, eventId) -> inbox.add(eventId), Fakes.DIRECT, clock, settings);
    private final RefundService service = new RefundService(payments, refunds, ledger, provider, events,
            (consumer, eventId) -> inbox.add(eventId), Fakes.DIRECT, clock, settings);

    private final UUID orderId = UUID.randomUUID();

    /** An order paid with {@code cardToken}: authorized, then captured; the events so far are forgotten. */
    private void captured(String cardToken) {
        paymentService.authorize(UUID.randomUUID(), orderId, UUID.randomUUID(), PAID, cardToken);
        paymentService.capture(UUID.randomUUID(), orderId);
        events.types().clear();
    }

    private UUID refund(String amount) {
        UUID returnId = UUID.randomUUID();
        service.refund(UUID.randomUUID(), orderId, returnId, Money.of(new BigDecimal(amount), "EUR"));
        return returnId;
    }

    private Refund refundOf(UUID returnId) {
        return refunds.findByReturnId(returnId).orElseThrow();
    }

    @Test
    void aRefundGivesTheMoneyBackAndReversesTheCaptureInTheLedger() {
        captured(SimulatedPaymentProvider.OK);

        UUID returnId = refund("30.00");

        assertThat(refundOf(returnId).status()).isEqualTo(Refund.Status.REFUNDED);
        assertThat(events.types()).containsExactly("refunded 30.00 EUR");
        assertThat(ledger.transactions).extracting(LedgerTransaction::kind)
                .containsExactly(LedgerTransaction.Kind.CAPTURE, LedgerTransaction.Kind.REFUND);
        assertThat(ledger.balance(Account.SALES, PAID.currency())).isEqualByComparingTo("-70.00");
        assertThat(ledger.balance(Account.PROVIDER_RECEIVABLE, PAID.currency())).isEqualByComparingTo("70.00");
    }

    @Test
    void refundsTogetherNeverExceedTheCapture() {
        captured(SimulatedPaymentProvider.OK);

        refund("60.00");
        UUID tooMuch = refund("50.00");
        refund("40.00");

        assertThat(refundOf(tooMuch).status()).isEqualTo(Refund.Status.FAILED);
        assertThat(refundOf(tooMuch).failureReason()).contains("would exceed the captured 100.00 EUR (60.00 already refunded)");
        assertThat(provider.refundCalls.get()).as("the refused refund never reached the provider").isEqualTo(2);
        assertThat(payments.refundedAmount(payments.findByOrderId(orderId).orElseThrow().id())).isEqualByComparingTo("100.00");
        assertThat(ledger.balance(Account.SALES, PAID.currency())).isEqualByComparingTo("0.00");
    }

    @Test
    void aRepeatedCommandForTheSameReturnRefundsOnce() {
        captured(SimulatedPaymentProvider.OK);
        UUID returnId = UUID.randomUUID();
        Money amount = Money.of(new BigDecimal("30.00"), "EUR");

        service.refund(UUID.randomUUID(), orderId, returnId, amount);
        service.refund(UUID.randomUUID(), orderId, returnId, amount); // replayed with a new event id

        assertThat(provider.refundCalls.get()).isEqualTo(1);
        assertThat(events.types()).containsExactly("refunded 30.00 EUR");
    }

    @Test
    void anUnansweredRefundIsAskedAgainWithTheSameKey() {
        captured(SimulatedPaymentProvider.OK);
        provider.unansweredRefunds = 1;
        provider.keys.clear();

        UUID returnId = refund("30.00");
        assertThat(refundOf(returnId).status()).isEqualTo(Refund.Status.REFUNDING);

        service.resolveInFlight(NOW.plusSeconds(1));

        assertThat(refundOf(returnId).status()).isEqualTo(Refund.Status.REFUNDED);
        assertThat(provider.keys).hasSize(2).containsOnly(refundOf(returnId).idempotencyKey());
        assertThat(events.types()).containsExactly("refunded 30.00 EUR");
    }

    @Test
    void aDeclinedRefundGivesItsAmountBackToTheLimit() {
        captured(SimulatedPaymentProvider.REFUND_FAILS);

        UUID returnId = refund("100.00");

        assertThat(refundOf(returnId).status()).isEqualTo(Refund.Status.FAILED);
        assertThat(events.types()).containsExactly("refundFailed: Card account closed");
        assertThat(payments.refundedAmount(payments.findByOrderId(orderId).orElseThrow().id())).isEqualByComparingTo("0");
        assertThat(ledger.transactions).extracting(LedgerTransaction::kind).containsExactly(LedgerTransaction.Kind.CAPTURE);
    }

    @Test
    void nothingIsRefundedForAPaymentThatWasNeverCaptured() {
        paymentService.authorize(UUID.randomUUID(), orderId, UUID.randomUUID(), PAID, SimulatedPaymentProvider.OK);
        events.types().clear();

        UUID returnId = refund("10.00");

        assertThat(refundOf(returnId).failureReason()).isEqualTo("Payment is AUTHORIZED, nothing captured to refund");
        assertThat(provider.refundCalls.get()).isZero();
    }
}
