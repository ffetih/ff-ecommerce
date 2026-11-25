package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
class ReservationEntity {

    @Id
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Reservation.Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // Lines never change after the reservation is placed; only the status moves.
    @ElementCollection
    @CollectionTable(name = "reservation_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderBy("sku")
    private List<ReservationLineEmbeddable> lines = new ArrayList<>();

    protected ReservationEntity() {
    }

    ReservationEntity(Reservation reservation) {
        this.orderId = reservation.orderId();
        this.createdAt = reservation.createdAt();
        reservation.lines().forEach(line ->
                lines.add(new ReservationLineEmbeddable(line.sku().value(), line.quantity())));
        update(reservation);
    }

    void update(Reservation reservation) {
        this.status = reservation.status();
        this.updatedAt = reservation.updatedAt();
    }

    Reservation toDomain() {
        List<Reservation.Line> domainLines = lines.stream()
                .map(line -> new Reservation.Line(Sku.of(line.sku()), line.quantity()))
                .toList();
        return new Reservation(orderId, domainLines, status, createdAt, updatedAt);
    }
}
