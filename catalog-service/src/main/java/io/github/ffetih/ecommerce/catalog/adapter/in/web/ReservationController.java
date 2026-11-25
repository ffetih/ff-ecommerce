package io.github.ffetih.ecommerce.catalog.adapter.in.web;

import io.github.ffetih.ecommerce.catalog.application.ReservationService;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Reservations are addressed by order id. {@code PUT} fits because the client chooses the id and
 * repeating the same request leaves the same state behind.
 */
@RestController
@RequestMapping("/api/v1/reservations")
class ReservationController {

    private final ReservationService reservations;

    ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PutMapping("/{orderId}")
    ReservationResponse reserve(@PathVariable UUID orderId, @Valid @RequestBody ReserveRequest request) {
        List<Reservation.Line> lines = request.lines().stream()
                .map(line -> new Reservation.Line(Sku.of(line.sku()), line.quantity()))
                .toList();
        return ReservationResponse.from(reservations.reserve(orderId, lines));
    }

    @GetMapping("/{orderId}")
    ReservationResponse get(@PathVariable UUID orderId) {
        return ReservationResponse.from(reservations.get(orderId));
    }

    @PostMapping("/{orderId}/commit")
    ReservationResponse commit(@PathVariable UUID orderId) {
        return ReservationResponse.from(reservations.commit(orderId));
    }

    @PostMapping("/{orderId}/release")
    ReservationResponse release(@PathVariable UUID orderId) {
        return ReservationResponse.from(reservations.release(orderId));
    }

    record ReserveRequest(@NotEmpty List<@Valid LineRequest> lines) {
    }

    record LineRequest(@NotBlank String sku, @Positive int quantity) {
    }

    record LineResponse(String sku, int quantity) {
    }

    record ReservationResponse(UUID orderId, Reservation.Status status, List<LineResponse> lines) {

        static ReservationResponse from(Reservation reservation) {
            return new ReservationResponse(
                    reservation.orderId(),
                    reservation.status(),
                    reservation.lines().stream()
                            .map(line -> new LineResponse(line.sku().value(), line.quantity()))
                            .toList());
        }
    }
}
