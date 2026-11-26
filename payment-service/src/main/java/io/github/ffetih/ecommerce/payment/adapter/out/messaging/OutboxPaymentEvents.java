package io.github.ffetih.ecommerce.payment.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.payment.PaymentAuthorized;
import io.github.ffetih.ecommerce.events.payment.PaymentCaptureFailed;
import io.github.ffetih.ecommerce.events.payment.PaymentCaptured;
import io.github.ffetih.ecommerce.events.payment.PaymentDeclined;
import io.github.ffetih.ecommerce.events.payment.PaymentVoided;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.payment.application.port.PaymentEvents;
import io.github.ffetih.ecommerce.payment.domain.Payment;
import org.springframework.stereotype.Component;

/** Keyed by order id, so one order's payment events stay in order on one partition. */
@Component
class OutboxPaymentEvents implements PaymentEvents {

    private final Outbox outbox;

    OutboxPaymentEvents(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void authorized(Payment payment) {
        append(payment, PaymentAuthorized.TYPE, PaymentAuthorized.VERSION, new PaymentAuthorized(payment.id(),
                payment.orderId(), payment.amount().amount(), payment.amount().currency().getCurrencyCode()));
    }

    @Override
    public void declined(Payment payment) {
        append(payment, PaymentDeclined.TYPE, PaymentDeclined.VERSION,
                new PaymentDeclined(payment.id(), payment.orderId(), payment.failureReason()));
    }

    @Override
    public void captured(Payment payment) {
        append(payment, PaymentCaptured.TYPE, PaymentCaptured.VERSION, new PaymentCaptured(payment.id(),
                payment.orderId(), payment.amount().amount(), payment.amount().currency().getCurrencyCode()));
    }

    @Override
    public void captureFailed(Payment payment) {
        append(payment, PaymentCaptureFailed.TYPE, PaymentCaptureFailed.VERSION,
                new PaymentCaptureFailed(payment.id(), payment.orderId(), payment.failureReason()));
    }

    @Override
    public void voided(Payment payment) {
        append(payment, PaymentVoided.TYPE, PaymentVoided.VERSION, new PaymentVoided(payment.id(), payment.orderId()));
    }

    private void append(Payment payment, String type, int version, Object data) {
        outbox.append(PaymentAuthorized.TOPIC, payment.orderId().toString(), type, version, data);
    }
}
