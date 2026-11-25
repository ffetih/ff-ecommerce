package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Order;

/** Announces order lifecycle changes. Implementations must record the event in the caller's transaction. */
public interface OrderEvents {

    void orderPlaced(Order order);

    void orderConfirmed(Order order);

    /** Also used as compensation: whoever still holds something for this order gives it back. */
    void orderCancelled(Order order);
}
