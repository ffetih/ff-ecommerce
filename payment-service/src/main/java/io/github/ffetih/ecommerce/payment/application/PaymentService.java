package io.github.ffetih.ecommerce.payment.application;

import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentEvents;
import io.github.ffetih.ecommerce.payment.application.port.PaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult;
import io.github.ffetih.ecommerce.payment.application.port.Transactions;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Payment.Operation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Carries out order-service's payment commands (ADR-0009). Every provider call takes three steps:
 * <pre>
 *   1. transaction: record the intent (AUTHORIZING, CAPTURING, VOIDING) and the command in the inbox
 *   2. no transaction: call the provider with the operation's idempotency key
 *   3. transaction: lock the payment, apply the answer, record the outcome event (outbox)
 * </pre>
 * No database transaction is held open while waiting for the provider. If step 2 has no answer, or
 * the service stops between the steps, the payment stays in flight and {@link #resolveInFlight} asks
 * the provider again later with the same key: the provider acts once per key, so asking again is safe.
 * A timeout is never turned into a decline.
 */
public class PaymentService {

    /** Inbox consumer name and Kafka consumer group of the command listener. */
    public static final String COMMANDS_CONSUMER = "payment-service.commands";

    /**
     * @param staleAfter how long a provider call may stay unanswered before the resolver asks again
     * @param batchSize  payments the resolver handles per run at most, oldest first
     */
    public record Settings(Duration staleAfter, int batchSize) {
    }

    private static final System.Logger log = System.getLogger(PaymentService.class.getName());

    private final PaymentRepository payments;
    private final Ledger ledger;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;
    private final Settings settings;

    public PaymentService(PaymentRepository payments, Ledger ledger, PaymentProvider provider, PaymentEvents events,
                          ProcessedEvents processedEvents, Transactions transactions, Clock clock, Settings settings) {
        this.payments = payments;
        this.ledger = ledger;
        this.provider = provider;
        this.events = events;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = settings;
    }

    /** {@code payment.authorize-payment}: hold the order's amount on the card. One payment per order. */
    public void authorize(UUID commandId, UUID orderId, UUID customerId, Money amount, String cardToken) {
        Optional<Payment> started = transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(COMMANDS_CONSUMER, commandId)) {
                return Optional.<Payment>empty();
            }
            if (payments.findByOrderId(orderId).isPresent()) {
                log.log(System.Logger.Level.WARNING, "Order {0} already has a payment; authorization ignored", orderId);
                return Optional.<Payment>empty();
            }
            Payment payment = Payment.start(orderId, customerId, amount, cardToken, clock.instant());
            payments.save(payment);
            return Optional.of(payment);
        });
        started.ifPresent(this::settle);
    }

    /** {@code payment.capture-payment}: the parcel has left, take the money. */
    public void capture(UUID commandId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(COMMANDS_CONSUMER, commandId)) {
                return Optional.<Payment>empty();
            }
            Payment current = payments.lockByOrderId(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
            return changed(current, current.requestCapture(clock.instant()));
        }).ifPresent(this::settle);
    }

    /**
     * {@code payment.void-payment}: the order was cancelled before its parcel left, release the hold.
     * While the authorization is still in flight, the request is remembered and applied when the
     * provider's approval arrives.
     */
    public void voidPayment(UUID commandId, UUID orderId) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(COMMANDS_CONSUMER, commandId)) {
                return Optional.<Payment>empty();
            }
            Optional<Payment> locked = payments.lockByOrderId(orderId);
            if (locked.isEmpty()) {
                // Only if the authorization command never got here (dead-lettered): nothing is held.
                log.log(System.Logger.Level.WARNING, "Void requested for order {0} without a payment", orderId);
                return Optional.<Payment>empty();
            }
            return changed(locked.get(), locked.get().requestVoid(clock.instant()));
        }).ifPresent(this::settle);
    }

    /**
     * Asks the provider again about every call that has had no answer for longer than
     * {@code staleAfter}. Returns how many payments it looked at.
     */
    public int resolveInFlight(Instant now) {
        List<Payment> waiting = transactions.inTransaction(() ->
                payments.findInFlight(now.minus(settings.staleAfter()), settings.batchSize()));
        waiting.forEach(this::settle);
        return waiting.size();
    }

    public Optional<Payment> forOrder(UUID orderId) {
        return transactions.inTransaction(() -> payments.findByOrderId(orderId));
    }

    private Optional<Payment> changed(Payment current, Payment next) {
        if (next == current) {
            return Optional.empty();
        }
        payments.save(next);
        return Optional.of(next);
    }

    /**
     * Steps 2 and 3 for whatever the payment is waiting for, repeated while the answer leads to
     * another call (an approval that arrives after the order was cancelled is voided straight away).
     * Never throws: what stays in flight is the resolver's to finish.
     */
    private void settle(Payment payment) {
        Payment current = payment;
        try {
            while (current != null && current.inFlight()) {
                Operation operation = current.operationInFlight();
                ProviderResult answer = call(current, operation);
                if (answer instanceof ProviderResult.Unknown unknown) {
                    log.log(System.Logger.Level.WARNING, "No answer to {0} of payment {1} ({2}); asking again later",
                            operation, current.id(), unknown.cause());
                    return;
                }
                UUID orderId = current.orderId();
                current = transactions.inTransaction(() -> apply(orderId, operation, answer));
            }
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Could not settle payment " + payment.id() + "; asking again later", e);
        }
    }

    private ProviderResult call(Payment payment, Operation operation) {
        String key = payment.idempotencyKey(operation);
        try {
            return switch (operation) {
                case AUTHORIZE -> provider.authorize(key, payment.amount(), payment.cardToken());
                case CAPTURE -> provider.capture(key, payment.providerReference(), payment.amount());
                case VOID -> provider.voidAuthorization(key, payment.providerReference());
            };
        } catch (RuntimeException e) {
            // A real client maps timeouts to Unknown; anything else it throws is no answer either.
            return new ProviderResult.Unknown(e.toString());
        }
    }

    /** Step 3. Returns the payment as it is now, or {@code null} if this answer was already applied. */
    private Payment apply(UUID orderId, Operation operation, ProviderResult answer) {
        Payment current = payments.lockByOrderId(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        if (current.operationInFlight() != operation) {
            return null; // the command handler and the resolver both asked; the other one applied it
        }
        Instant now = clock.instant();
        Payment next = switch (answer) {
            case ProviderResult.Approved approved -> approved(current, operation, approved.reference(), now);
            case ProviderResult.Declined declined -> declined(current, operation, declined.reason(), now);
            case ProviderResult.Unknown unknown -> throw new IllegalStateException("Unknown is never applied");
        };
        payments.save(next);
        return next;
    }

    private Payment approved(Payment current, Operation operation, String reference, Instant now) {
        return switch (operation) {
            case AUTHORIZE -> {
                Payment next = current.authorized(reference, now);
                if (next.status() == Payment.Status.AUTHORIZED) {
                    events.authorized(next);
                }
                // VOIDING: the order was cancelled meanwhile; the void follows, and its event.
                yield next;
            }
            case CAPTURE -> {
                Payment next = current.captured(now);
                ledger.book(LedgerTransaction.capture(next, now));
                events.captured(next);
                yield next;
            }
            case VOID -> {
                Payment next = current.voided(now);
                events.voided(next);
                yield next;
            }
        };
    }

    private Payment declined(Payment current, Operation operation, String reason, Instant now) {
        return switch (operation) {
            case AUTHORIZE -> {
                Payment next = current.declined(reason, now);
                events.declined(next);
                yield next;
            }
            case CAPTURE -> {
                Payment next = current.captureFailed(reason, now);
                events.captureFailed(next);
                yield next;
            }
            case VOID -> {
                // The provider will not release the hold (it may already have expired). No money was
                // taken, and an authorization lapses at the provider on its own.
                log.log(System.Logger.Level.WARNING, "Provider refused to void payment {0}: {1}", current.id(), reason);
                Payment next = current.voided(now);
                events.voided(next);
                yield next;
            }
        };
    }
}
