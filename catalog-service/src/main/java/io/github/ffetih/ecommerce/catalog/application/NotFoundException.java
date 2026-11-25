package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.domain.Sku;

import java.util.UUID;

public class NotFoundException extends RuntimeException {

    private NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException product(Sku sku) {
        return new NotFoundException("Product not found: " + sku);
    }

    public static NotFoundException reservation(UUID orderId) {
        return new NotFoundException("Reservation not found for order " + orderId);
    }
}
