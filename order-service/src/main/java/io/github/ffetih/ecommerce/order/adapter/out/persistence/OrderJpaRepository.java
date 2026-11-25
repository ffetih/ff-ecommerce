package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, UUID idempotencyKey);

    List<OrderEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /** Served by the partial index {@code orders_awaiting_progress} (V5). */
    @Query(value = """
            SELECT id FROM orders
            WHERE (status = 'PLACED' AND updated_at < :placedBefore)
               OR (status = 'CONFIRMED' AND updated_at < :confirmedBefore)
            ORDER BY updated_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findStaleIds(@Param("placedBefore") Instant placedBefore,
                            @Param("confirmedBefore") Instant confirmedBefore,
                            @Param("limit") int limit);

    @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OrderEntity> lockIfFree(@Param("id") UUID id);
}
