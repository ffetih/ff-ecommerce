package io.github.ffetih.ecommerce.shipping.adapter.out.persistence;

import io.github.ffetih.ecommerce.shipping.application.port.ShipmentRepository;
import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Callers run these inside a transaction (ADR-0004); mapping reads the lazy line collection. */
@Component
class ShipmentPersistenceAdapter implements ShipmentRepository {

    private final ShipmentJpaRepository repository;

    ShipmentPersistenceAdapter(ShipmentJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Shipment> findById(UUID shipmentId) {
        return repository.findById(shipmentId).map(ShipmentEntity::toDomain);
    }

    @Override
    public Optional<Shipment> findByOrderId(UUID orderId) {
        return repository.findByOrderId(orderId).map(ShipmentEntity::toDomain);
    }

    @Override
    public void save(Shipment shipment) {
        ShipmentEntity entity = repository.findById(shipment.id())
                .map(existing -> {
                    existing.update(shipment);
                    return existing;
                })
                .orElseGet(() -> new ShipmentEntity(shipment));
        repository.save(entity);
    }
}
