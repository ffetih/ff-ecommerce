package io.github.ffetih.ecommerce.payment.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.payment.AuthorizePayment;
import io.github.ffetih.ecommerce.events.payment.CapturePayment;
import io.github.ffetih.ecommerce.events.payment.VoidPayment;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import io.github.ffetih.ecommerce.payment.application.PaymentService;
import io.github.ffetih.ecommerce.payment.domain.Money;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PaymentCommandListener {

    private final EventReader reader;
    private final PaymentService payments;

    PaymentCommandListener(EventReader reader, PaymentService payments) {
        this.reader = reader;
        this.payments = payments;
    }

    @KafkaListener(topics = AuthorizePayment.TOPIC, groupId = PaymentService.COMMANDS_CONSUMER)
    public void on(String message) {
        EventEnvelope command = reader.read(message);
        switch (command.eventType()) {
            case AuthorizePayment.TYPE -> {
                AuthorizePayment authorize = reader.data(command, AuthorizePayment.class);
                payments.authorize(command.eventId(), authorize.orderId(), authorize.customerId(),
                        Money.of(authorize.amount(), authorize.currency()), authorize.cardToken());
            }
            case CapturePayment.TYPE ->
                    payments.capture(command.eventId(), reader.data(command, CapturePayment.class).orderId());
            case VoidPayment.TYPE ->
                    payments.voidPayment(command.eventId(), reader.data(command, VoidPayment.class).orderId());
            default -> throw new IllegalArgumentException("Unknown payment command " + command.eventType());
        }
    }
}
