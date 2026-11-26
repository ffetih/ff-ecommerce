package io.github.ffetih.ecommerce.order.adapter.in.messaging;

import io.github.ffetih.ecommerce.events.payment.PaymentAuthorized;
import io.github.ffetih.ecommerce.events.payment.PaymentCaptureFailed;
import io.github.ffetih.ecommerce.events.payment.PaymentCaptured;
import io.github.ffetih.ecommerce.events.payment.PaymentDeclined;
import io.github.ffetih.ecommerce.events.payment.PaymentVoided;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.messaging.inbox.EventReader;
import io.github.ffetih.ecommerce.order.application.OrderSagaService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PaymentListener {

    private final EventReader reader;
    private final OrderSagaService saga;

    PaymentListener(EventReader reader, OrderSagaService saga) {
        this.reader = reader;
        this.saga = saga;
    }

    @KafkaListener(topics = PaymentAuthorized.TOPIC, groupId = OrderSagaService.PAYMENT_EVENTS_CONSUMER)
    public void on(String message) {
        EventEnvelope event = reader.read(message);
        switch (event.eventType()) {
            case PaymentAuthorized.TYPE -> saga.paymentAuthorized(
                    event.eventId(), reader.data(event, PaymentAuthorized.class).orderId());
            case PaymentDeclined.TYPE -> {
                PaymentDeclined declined = reader.data(event, PaymentDeclined.class);
                saga.paymentDeclined(event.eventId(), declined.orderId(), declined.reason());
            }
            case PaymentCaptured.TYPE -> saga.paymentCaptured(
                    event.eventId(), reader.data(event, PaymentCaptured.class).orderId());
            case PaymentCaptureFailed.TYPE -> {
                PaymentCaptureFailed failed = reader.data(event, PaymentCaptureFailed.class);
                saga.paymentCaptureFailed(event.eventId(), failed.orderId(), failed.reason());
            }
            case PaymentVoided.TYPE -> saga.paymentVoided(
                    event.eventId(), reader.data(event, PaymentVoided.class).orderId());
            default -> {
                // not relevant for orders
            }
        }
    }
}
