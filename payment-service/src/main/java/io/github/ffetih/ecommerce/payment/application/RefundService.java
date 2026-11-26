package io.github.ffetih.ecommerce.payment.application;

import io.github.ffetih.ecommerce.payment.application.port.Ledger;
import io.github.ffetih.ecommerce.payment.application.port.PaymentEvents;
import io.github.ffetih.ecommerce.payment.application.port.PaymentProvider;
import io.github.ffetih.ecommerce.payment.application.port.PaymentRepository;
import io.github.ffetih.ecommerce.payment.application.port.ProcessedEvents;
import io.github.ffetih.ecommerce.payment.application.port.ProviderResult;
import io.github.ffetih.ecommerce.payment.application.port.RefundRepository;
import io.github.ffetih.ecommerce.payment.application.port.Transactions;
import io.github.ffetih.ecommerce.payment.domain.LedgerTransaction;
import io.github.ffetih.ecommerce.payment.domain.Money;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Refund;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refunds for returns (ADR-0010), with the same three steps as every provider call (ADR-0009):
 * <pre>
 *   1. transaction: count the amount as refunded on the payment (refused if it would exceed the
 *      capture), store the refund REFUNDING, record the command in the inbox
 *   2. no transaction: call the provider with key "&lt;paymentId&gt;:refund:&lt;returnId&gt;"
 *   3. transaction: lock the refund, apply the answer, book the ledger, outbox the event
 * </pre>
 * The amount is counted in step 1, before the provider is asked, so two refunds at the same moment
 * cannot both pass the limit; a declined refund gives its amount back in step 3.
 */
public class RefundService {

    private static final System.Logger log = System.getLogger(RefundService.class.getName());

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final Ledger ledger;
    private final PaymentProvider provider;
    private final PaymentEvents events;
    private final ProcessedEvents processedEvents;
    private final Transactions transactions;
    private final Clock clock;
    private final PaymentService.Settings settings;

    public RefundService(PaymentRepository payments, RefundRepository refunds, Ledger ledger, PaymentProvider provider,
                         PaymentEvents events, ProcessedEvents processedEvents, Transactions transactions, Clock clock,
                         PaymentService.Settings settings) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.provider = provider;
        this.events = events;
        this.processedEvents = processedEvents;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = settings;
    }

    /** {@code payment.refund-payment}: give the money for one return back. One refund per return. */
    public void refund(UUID commandId, UUID orderId, UUID returnId, Money amount) {
        transactions.inTransaction(() -> {
            if (!processedEvents.firstDelivery(PaymentService.COMMANDS_CONSUMER, commandId)) {
                return Optional.<Refund>empty();
            }
            if (refunds.findByReturnId(returnId).isPresent()) {
                log.log(System.Logger.Level.WARNING, "Return {0} already has a refund; command ignored", returnId);
                return Optional.<Refund>empty();
            }
            Payment payment = payments.lockByOrderId(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
            Instant now = clock.instant();
            if (!payments.reserveRefund(payment.id(), amount.amount())) {
                Refund refused = Refund.refused(payment, returnId, amount, refusal(payment, amount), now);
                refunds.save(refused);
                events.refundFailed(refused);
                return Optional.<Refund>empty();
            }
            Refund started = Refund.start(payment, returnId, amount, now);
            refunds.save(started);
            return Optional.of(started);
        }).ifPresent(this::settle);
    }

    /** Asks the provider again about refunds without an answer for longer than {@code staleAfter}. */
    public int resolveInFlight(Instant now) {
        List<Refund> waiting = transactions.inTransaction(() ->
                refunds.findInFlight(now.minus(settings.staleAfter()), settings.batchSize()));
        waiting.forEach(this::settle);
        return waiting.size();
    }

    public List<Refund> forOrder(UUID orderId) {
        return transactions.inTransaction(() -> refunds.findByOrderId(orderId));
    }

    /** Steps 2 and 3. Never throws: what stays in flight is the resolver's to finish. */
    private void settle(Refund refund) {
        try {
            Payment payment = transactions.inTransaction(() -> payments.findByOrderId(refund.orderId()).orElseThrow());
            ProviderResult answer;
            try {
                answer = provider.refund(refund.idempotencyKey(), payment.providerReference(), refund.amount());
            } catch (RuntimeException e) {
                answer = new ProviderResult.Unknown(e.toString());
            }
            if (answer instanceof ProviderResult.Unknown unknown) {
                log.log(System.Logger.Level.WARNING, "No answer to refund {0} ({1}); asking again later",
                        refund.id(), unknown.cause());
                return;
            }
            ProviderResult known = answer;
            transactions.inTransaction(() -> apply(refund.id(), known));
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Could not settle refund " + refund.id() + "; asking again later", e);
        }
    }

    private Refund apply(UUID refundId, ProviderResult answer) {
        Refund current = refunds.lockById(refundId).orElseThrow();
        if (!current.inFlight()) {
            return current; // the command handler and the resolver both asked; the other one applied it
        }
        Instant now = clock.instant();
        Refund next = switch (answer) {
            case ProviderResult.Approved approved -> {
                Refund refunded = current.refunded(approved.reference(), now);
                ledger.book(LedgerTransaction.refund(refunded, now));
                events.refunded(refunded);
                yield refunded;
            }
            case ProviderResult.Declined declined -> {
                Refund failed = current.failed(declined.reason(), now);
                payments.releaseRefund(current.paymentId(), current.amount().amount());
                events.refundFailed(failed);
                yield failed;
            }
            case ProviderResult.Unknown unknown -> throw new IllegalStateException("Unknown is never applied");
        };
        refunds.save(next);
        return next;
    }

    private String refusal(Payment payment, Money amount) {
        if (payment.status() != Payment.Status.CAPTURED) {
            return "Payment is " + payment.status() + ", nothing captured to refund";
        }
        return "A refund of " + amount + " would exceed the captured " + payment.amount() + " ("
                + payments.refundedAmount(payment.id()) + " already refunded)";
    }
}
