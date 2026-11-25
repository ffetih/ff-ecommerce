package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface ReservationJpaRepository extends JpaRepository<ReservationEntity, UUID> {

    // No fetch join: PostgreSQL rejects FOR UPDATE on the nullable side of an outer join.
    // Lines are loaded lazily afterwards, inside the same transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReservationEntity r WHERE r.orderId = :orderId")
    Optional<ReservationEntity> lockById(@Param("orderId") UUID orderId);
}
