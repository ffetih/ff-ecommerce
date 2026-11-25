package io.github.ffetih.ecommerce.shipping.domain;

import java.util.UUID;

public class IllegalShipmentTransitionException extends RuntimeException {

    public IllegalShipmentTransitionException(UUID shipmentId, Shipment.Status from, String action) {
        super("Cannot " + action + " shipment " + shipmentId + " in status " + from);
    }
}
