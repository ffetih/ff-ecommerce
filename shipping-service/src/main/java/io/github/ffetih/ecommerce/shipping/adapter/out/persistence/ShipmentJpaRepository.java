package io.github.ffetih.ecommerce.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ShipmentJpaRepository extends JpaRepository<ShipmentEntity, UUID> {

    Optional<ShipmentEntity> findByOrderId(UUID orderId);
}
