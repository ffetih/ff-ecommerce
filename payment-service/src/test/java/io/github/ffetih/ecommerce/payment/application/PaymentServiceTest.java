package io.github.ffetih.ecommerce.payment.application;

import io.github.ffetih.ecommerce.payment.adapter.out.provider.SimulatedPaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentEvents;
import io.github.ffetih.ecommerce.payment.application.port.PaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult;
import io.github.ffetih.ecommerce.payment.application.port.Transactions;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.IllegalPaymentTransitionException;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Payment.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
            (consumer, eventId) -> inbox.add(eventId), DIRECT, Clock.fixed(NOW, ZoneOffset.UTC),
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

    // --- in-memory ports -----------------------------------------------------------------------

    private static final Transactions DIRECT = new Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };

    private static final class InMemoryPayments implements PaymentRepository {
        private final Map<UUID, Payment> byOrder = new HashMap<>();

        @Override
        public Optional<Payment> findByOrderId(UUID orderId) {
            return Optional.ofNullable(byOrder.get(orderId));
        }

        @Override
        public Optional<Payment> lockByOrderId(UUID orderId) {
            return findByOrderId(orderId);
        }

        @Override
        public List<Payment> findInFlight(Instant updatedBefore, int limit) {
            return byOrder.values().stream()
                    .filter(Payment::inFlight)
                    .filter(payment -> payment.updatedAt().isBefore(updatedBefore))
                    .sorted(Comparator.comparing(Payment::updatedAt))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void save(Payment payment) {
            byOrder.put(payment.orderId(), payment);
        }
    }

    private static final class InMemoryLedger implements Ledger {
        private final List<LedgerTransaction> transactions = new ArrayList<>();

        @Override
        public boolean book(LedgerTransaction transaction) {
            if (transactions.stream().anyMatch(t -> t.reference().equals(transaction.reference()))) {
                return false;
            }
            return transactions.add(transaction);
        }

        @Override
        public List<LedgerTransaction> transactionsOf(UUID paymentId) {
            return transactions.stream().filter(t -> t.paymentId().equals(paymentId)).toList();
        }

        @Override
        public BigDecimal balance(Account account, Currency currency) {
            return transactions.stream().filter(t -> t.currency().equals(currency))
                    .flatMap(t -> t.entries().stream())
                    .filter(entry -> entry.account() == account)
                    .map(entry -> entry.amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private static final class RecordedEvents implements PaymentEvents {
        private final List<String> types = new ArrayList<>();

        List<String> types() {
            return types;
        }

        @Override
        public void authorized(Payment payment) {
            types.add("authorized");
        }

        @Override
        public void declined(Payment payment) {
            types.add("declined");
        }

        @Override
        public void captured(Payment payment) {
            types.add("captured");
        }

        @Override
        public void captureFailed(Payment payment) {
            types.add("captureFailed");
        }

        @Override
        public void voided(Payment payment) {
            types.add("voided");
        }
    }

    /** Counts what reaches the provider; can lose answers or fail a call, as a real network would. */
    private static final class CountingProvider implements PaymentProvider {
        private final PaymentProvider delegate;
        private final AtomicInteger authorizeCalls = new AtomicInteger();
        private final AtomicInteger captureCalls = new AtomicInteger();
        private final List<String> keys = new ArrayList<>();
        private boolean failNext;
        private int unansweredCalls;

        CountingProvider(PaymentProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProviderResult authorize(String idempotencyKey, Money amount, String cardToken) {
            failIfAsked();
            authorizeCalls.incrementAndGet();
            keys.add(idempotencyKey);
            ProviderResult answer = delegate.authorize(idempotencyKey, amount, cardToken);
            if (unansweredCalls > 0) {
                unansweredCalls--;
                return new ProviderResult.Unknown("answer lost");
            }
            return answer;
        }

        @Override
        public ProviderResult capture(String idempotencyKey, String authorization, Money amount) {
            captureCalls.incrementAndGet();
            return delegate.capture(idempotencyKey, authorization, amount);
        }

        @Override
        public ProviderResult voidAuthorization(String idempotencyKey, String authorization) {
            return delegate.voidAuthorization(idempotencyKey, authorization);
        }

        private void failIfAsked() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("connection reset");
            }
        }
    }
}
