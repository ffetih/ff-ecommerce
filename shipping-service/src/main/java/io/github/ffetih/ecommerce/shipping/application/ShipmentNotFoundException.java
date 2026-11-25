package io.github.ffetih.ecommerce.shipping.application;

import java.util.UUID;

public class ShipmentNotFoundException extends RuntimeException {

    public ShipmentNotFoundException(String what, UUID id) {
        super("No shipment for " + what + " " + id);
    }
}
