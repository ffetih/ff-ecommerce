package io.github.ffetih.ecommerce.payment.application.port;

import io.github.ffetih.ecommerce.payment.domain.Payment;
import io.github.ffetih.ecommerce.payment.domain.Refund;

/** Announces payment outcomes to order-service. Recorded in the caller's transaction (outbox). */
public interface PaymentEvents {

    void authorized(Payment payment);

    void declined(Payment payment);

    void captured(Payment payment);

    void captureFailed(Payment payment);

    void voided(Payment payment);

    void refunded(Refund refund);

    void refundFailed(Refund refund);
}
