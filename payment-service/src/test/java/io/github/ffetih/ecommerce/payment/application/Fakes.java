package io.github.ffetih.ecommerce.payment.application;

import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentEvents;
import io.github.ffetih.ecommerce.payment.application.port.PaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult;
import io.github.ffetih.ecommerce.payment.application.port.RefundRepository;
import io.github.ffetih.ecommerce.payment.application.port.Transactions;
import io.github.ffetih.ecommerce.payment.domain.Account;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** In-memory ports for the application services' unit tests. */
final class Fakes {

    private Fakes() {
    }

    static final Transactions DIRECT = new Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };

    static final class InMemoryPayments implements PaymentRepository {
        private final Map<UUID, Payment> byOrder = new HashMap<>();
        private final Map<UUID, BigDecimal> refunded = new HashMap<>();

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

        /** The same rule as the SQL {@code WHERE}: captured, and within the captured amount. */
        @Override
        public boolean reserveRefund(UUID paymentId, BigDecimal amount) {
            Payment payment = byId(paymentId);
            BigDecimal after = refundedAmount(paymentId).add(amount);
            if (payment.status() != Payment.Status.CAPTURED || after.compareTo(payment.amount().amount()) > 0) {
                return false;
            }
            refunded.put(paymentId, after);
            return true;
        }

        @Override
        public void releaseRefund(UUID paymentId, BigDecimal amount) {
            refunded.put(paymentId, refundedAmount(paymentId).subtract(amount));
        }

        @Override
        public BigDecimal refundedAmount(UUID paymentId) {
            return refunded.getOrDefault(paymentId, BigDecimal.ZERO);
        }

        private Payment byId(UUID paymentId) {
            return byOrder.values().stream().filter(payment -> payment.id().equals(paymentId)).findFirst().orElseThrow();
        }
    }

    static final class InMemoryRefunds implements RefundRepository {
        private final Map<UUID, Refund> byId = new HashMap<>();

        @Override
        public Optional<Refund> findByReturnId(UUID returnId) {
            return byId.values().stream().filter(refund -> refund.returnId().equals(returnId)).findFirst();
        }

        @Override
        public Optional<Refund> lockById(UUID refundId) {
            return Optional.ofNullable(byId.get(refundId));
        }

        @Override
        public List<Refund> findByOrderId(UUID orderId) {
            return byId.values().stream().filter(refund -> refund.orderId().equals(orderId))
                    .sorted(Comparator.comparing(Refund::createdAt)).toList();
        }

        @Override
        public List<Refund> findInFlight(Instant updatedBefore, int limit) {
            return byId.values().stream()
                    .filter(Refund::inFlight)
                    .filter(refund -> refund.updatedAt().isBefore(updatedBefore))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void save(Refund refund) {
            byId.put(refund.id(), refund);
        }
    }

    static final class InMemoryLedger implements Ledger {
        final List<LedgerTransaction> transactions = new ArrayList<>();

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

    static final class RecordedEvents implements PaymentEvents {
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

        @Override
        public void refunded(Refund refund) {
            types.add("refunded " + refund.amount());
        }

        @Override
        public void refundFailed(Refund refund) {
            types.add("refundFailed: " + refund.failureReason());
        }
    }

    /** Counts what reaches the provider; can lose answers or fail a call, as a real network would. */
    static final class CountingProvider implements PaymentProvider {
        private final PaymentProvider delegate;
        final AtomicInteger authorizeCalls = new AtomicInteger();
        final AtomicInteger captureCalls = new AtomicInteger();
        final AtomicInteger refundCalls = new AtomicInteger();
        final List<String> keys = new ArrayList<>();
        boolean failNext;
        int unansweredCalls;
        int unansweredRefunds;

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

        @Override
        public ProviderResult refund(String idempotencyKey, String authorization, Money amount) {
            refundCalls.incrementAndGet();
            keys.add(idempotencyKey);
            ProviderResult answer = delegate.refund(idempotencyKey, authorization, amount);
            if (unansweredRefunds > 0) {
                unansweredRefunds--;
                return new ProviderResult.Unknown("answer lost");
            }
            return answer;
        }

        private void failIfAsked() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("connection reset");
            }
        }
    }
}
