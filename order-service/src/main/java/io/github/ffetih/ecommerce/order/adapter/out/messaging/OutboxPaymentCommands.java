package io.github.ffetih.ecommerce.order.adapter.out.messaging;

import io.github.ffetih.ecommerce.events.payment.AuthorizePayment;
import io.github.ffetih.ecommerce.events.payment.CapturePayment;
import io.github.ffetih.ecommerce.events.payment.VoidPayment;
import io.github.ffetih.ecommerce.messaging.outbox.Outbox;
import io.github.ffetih.ecommerce.order.application.port.PaymentCommands;
import io.github.ffetih.ecommerce.order.domain.Order;
import org.springframework.stereotype.Component;

/** Keyed by order id: one order's commands reach payment-service in the order they were sent. */
@Component
class OutboxPaymentCommands implements PaymentCommands {

    private final Outbox outbox;

    OutboxPaymentCommands(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void authorize(Order order) {
        append(order, AuthorizePayment.TYPE, AuthorizePayment.VERSION, new AuthorizePayment(order.id(), order.customerId(),
                order.total().amount(), order.total().currency().getCurrencyCode(), order.cardToken()));
    }

    @Override
    public void capture(Order order) {
        append(order, CapturePayment.TYPE, CapturePayment.VERSION, new CapturePayment(order.id()));
    }

    @Override
    public void voidPayment(Order order) {
        append(order, VoidPayment.TYPE, VoidPayment.VERSION, new VoidPayment(order.id()));
    }

    private void append(Order order, String type, int version, Object command) {
        outbox.append(AuthorizePayment.TOPIC, order.id().toString(), type, version, command);
    }
}
