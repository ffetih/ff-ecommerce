package io.github.ffetih.ecommerce.payment.application;

import io.github.ffetih.ecommerce.payment.adapter.out.provider.SimulatedPaymentProvider;
import io.github.ffetih.ecommerce.payment.application.Fakes.CountingProvider;
import io.github.ffetih.ecommerce.payment.application.Fakes.InMemoryLedger;
import io.github.ffetih.ecommerce.payment.application.Fakes.InMemoryPayments;
import io.github.ffetih.ecommerce.payment.application.Fakes.RecordedEvents;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.IllegalPaymentTransitionException;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Payment.Status;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The command and resolver rules, with in-memory ports and the simulated provider. */
class PaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final Money AMOUNT = Money.of(new BigDecimal("199.90"), "TRY");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final RecordedEvents events = new RecordedEvents();
    private final CountingProvider provider = new CountingProvider(new SimulatedPaymentProvider());
    private final Set<UUID> inbox = new HashSet<>();
    private final PaymentService service = new PaymentService(payments, ledger, provider, events,
            (consumer, eventId) -> inbox.add(eventId), Fakes.DIRECT, Clock.fixed(NOW, ZoneOffset.UTC),
            new PaymentService.Settings(Duration.ZERO, 10));

    private final UUID orderId = UUID.randomUUID();

    private void authorize(String cardToken) {
        service.authorize(UUID.randomUUID(), orderId, UUID.randomUUID(), AMOUNT, cardToken);
    }

    private Payment payment() {
        return service.forOrder(orderId).orElseThrow();
    }

    @Test
    void anApprovedAuthorizationIsAnnounced() {
        authorize(SimulatedPaymentProvider.OK);

        assertThat(payment().status()).isEqualTo(Status.AUTHORIZED);
        assertThat(payment().providerReference()).startsWith("AUTH-");
        assertThat(events.types()).containsExactly("authorized");
        assertThat(ledger.transactions).as("a hold moves no money").isEmpty();
    }

    @Test
    void aDeclineIsAnnouncedWithItsReason() {
        authorize(SimulatedPaymentProvider.DECLINE);

        assertThat(payment().status()).isEqualTo(Status.DECLINED);
        assertThat(payment().failureReason()).isEqualTo("Card declined");
        assertThat(events.types()).containsExactly("declined");
    }

    @Test
    void aRedeliveredCommandIsIgnored() {
        UUID command = UUID.randomUUID();
        service.authorize(command, orderId, UUID.randomUUID(), AMOUNT, SimulatedPaymentProvider.OK);
        service.authorize(command, orderId, UUID.randomUUID(), AMOUNT, SimulatedPaymentProvider.OK);
        service.authorize(UUID.randomUUID(), orderId, UUID.randomUUID(), AMOUNT, SimulatedPaymentProvider.OK);

        assertThat(provider.authorizeCalls.get()).isEqualTo(1);
        assertThat(events.types()).containsExactly("authorized");
    }

    @Test
    void noAnswerIsNotADeclineTheResolverAsksAgainWithTheSameKey() {
        authorize(SimulatedPaymentProvider.SLOW);

        assertThat(payment().status()).isEqualTo(Status.AUTHORIZING);
        assertThat(events.types()).isEmpty();

        assertThat(service.resolveInFlight(NOW.plusSeconds(1))).isEqualTo(1);

        assertThat(payment().status()).isEqualTo(Status.AUTHORIZED);
        assertThat(provider.keys).containsExactly(payment().idempotencyKey(Payment.Operation.AUTHORIZE),
                payment().idempotencyKey(Payment.Operation.AUTHORIZE));
        assertThat(events.types()).containsExactly("authorized");
    }

    @Test
    void aCancellationDuringAnUnansweredAuthorizationIsRememberedAndReleasesTheHoldWhenTheAnswerComes() {
        provider.unansweredCalls = 2; // the provider acts, but its answers are lost twice
        authorize(SimulatedPaymentProvider.OK);
        service.voidPayment(UUID.randomUUID(), orderId);

        assertThat(payment().status()).isEqualTo(Status.AUTHORIZING);
        assertThat(payment().voidRequested()).isTrue();

        service.resolveInFlight(NOW.plusSeconds(1));

        assertThat(payment().status()).isEqualTo(Status.VOIDED);
        assertThat(events.types()).as("never announced as authorized: the order is cancelled").containsExactly("voided");
    }

    @Test
    void anAuthorizedHoldIsReleasedOnCancellation() {
        authorize(SimulatedPaymentProvider.OK);

        service.voidPayment(UUID.randomUUID(), orderId);

        assertThat(payment().status()).isEqualTo(Status.VOIDED);
        assertThat(events.types()).containsExactly("authorized", "voided");
    }

    @Test
    void aCaptureBooksTheLedgerOnceEvenIfRepeated() {
        authorize(SimulatedPaymentProvider.OK);

        service.capture(UUID.randomUUID(), orderId);
        service.capture(UUID.randomUUID(), orderId);

        assertThat(payment().status()).isEqualTo(Status.CAPTURED);
        assertThat(provider.captureCalls.get()).isEqualTo(1);
        assertThat(events.types()).containsExactly("authorized", "captured");
        assertThat(ledger.balance(Account.PROVIDER_RECEIVABLE, AMOUNT.currency())).isEqualByComparingTo("199.90");
        assertThat(ledger.balance(Account.SALES, AMOUNT.currency())).isEqualByComparingTo("-199.90");
    }

    @Test
    void aFailedCaptureIsAnnouncedAndBooksNothing() {
        authorize(SimulatedPaymentProvider.CAPTURE_FAILS);

        service.capture(UUID.randomUUID(), orderId);

        assertThat(payment().status()).isEqualTo(Status.CAPTURE_FAILED);
        assertThat(events.types()).containsExactly("authorized", "captureFailed");
        assertThat(ledger.transactions).isEmpty();
    }

    @Test
    void capturedMoneyCannotBeVoidedAndAnUnknownOrderCannotBeCaptured() {
        authorize(SimulatedPaymentProvider.OK);
        service.capture(UUID.randomUUID(), orderId);

        assertThatThrownBy(() -> service.voidPayment(UUID.randomUUID(), orderId))
                .isInstanceOf(IllegalPaymentTransitionException.class);
        assertThatThrownBy(() -> service.capture(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void aProviderThatThrowsLeavesThePaymentInFlight() {
        provider.failNext = true;
        authorize(SimulatedPaymentProvider.OK);

        assertThat(payment().status()).isEqualTo(Status.AUTHORIZING);

        service.resolveInFlight(NOW.plusSeconds(1));
        assertThat(payment().status()).isEqualTo(Status.AUTHORIZED);
    }
}
