package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.port.ReservationRepository;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class ReservationPersistenceAdapter implements ReservationRepository {

    private final ReservationJpaRepository repository;

    ReservationPersistenceAdapter(ReservationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Reservation> findById(UUID orderId) {
        return repository.findById(orderId).map(ReservationEntity::toDomain);
    }

    @Override
    public Optional<Reservation> lockById(UUID orderId) {
        return repository.lockById(orderId).map(ReservationEntity::toDomain);
    }

    @Override
    public void save(Reservation reservation) {
        ReservationEntity entity = repository.findById(reservation.orderId())
                .map(existing -> {
                    existing.update(reservation);
                    return existing;
                })
                .orElseGet(() -> new ReservationEntity(reservation));
        repository.save(entity);
    }
}
