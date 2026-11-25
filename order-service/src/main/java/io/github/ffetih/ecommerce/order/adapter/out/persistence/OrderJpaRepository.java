package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, UUID idempotencyKey);

    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}
