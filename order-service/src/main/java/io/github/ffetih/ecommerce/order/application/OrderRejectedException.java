package io.github.ffetih.ecommerce.order.application;

/** The order cannot be accepted as requested: unknown customer or product, inactive account, and so on. */
public class OrderRejectedException extends RuntimeException {

    public OrderRejectedException(String message) {
        super(message);
    }
}
