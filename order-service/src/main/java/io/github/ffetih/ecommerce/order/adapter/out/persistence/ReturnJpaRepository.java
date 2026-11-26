package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReturnJpaRepository extends JpaRepository<ReturnEntity, UUID> {

    List<ReturnEntity> findByOrderIdOrderByCreatedAt(UUID orderId);

    Optional<ReturnEntity> findByOrderIdAndIdempotencyKey(UUID orderId, UUID idempotencyKey);
}
