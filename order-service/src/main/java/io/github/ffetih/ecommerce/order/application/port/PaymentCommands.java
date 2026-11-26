package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.Return;

/** Commands to payment-service (ADR-0009). Recorded in the caller's transaction (outbox). */
public interface PaymentCommands {

    void authorize(Order order);

    void capture(Order order);

    void voidPayment(Order order);

    /** Gives the money for a received return back (ADR-0010). */
    void refund(Return received);
}
