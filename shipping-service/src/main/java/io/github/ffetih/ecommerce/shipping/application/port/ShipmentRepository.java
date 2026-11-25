package io.github.ffetih.ecommerce.shipping.application.port;

import io.github.ffetih.ecommerce.shipping.domain.Shipment;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository {

    Optional<Shipment> findById(UUID shipmentId);

    Optional<Shipment> findByOrderId(UUID orderId);

    void save(Shipment shipment);
}
