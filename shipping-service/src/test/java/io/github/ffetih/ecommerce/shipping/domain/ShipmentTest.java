package io.github.ffetih.ecommerce.shipping.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShipmentTest {

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    private static Shipment created() {
        return Shipment.create(UUID.randomUUID(), UUID.randomUUID(), List.of(new ShipmentLine("KB-01", 2)),
                "Bağdat Cd. 1, İstanbul", NOW);
    }

    @Test
    void dispatchAssignsATrackingNumberOnce() {
        Shipment dispatched = created().dispatch("TRK-1", NOW);

        assertThat(dispatched.status()).isEqualTo(Shipment.Status.DISPATCHED);
        assertThat(dispatched.trackingNumber()).isEqualTo("TRK-1");
        assertThat(dispatched.dispatch("TRK-2", NOW)).isSameAs(dispatched);
    }

    @Test
    void deliveryNeedsADispatchFirst() {
        assertThatThrownBy(() -> created().deliver(NOW)).isInstanceOf(IllegalShipmentTransitionException.class);
        assertThat(created().dispatch("TRK-1", NOW).deliver(NOW).status()).isEqualTo(Shipment.Status.DELIVERED);
    }

    @Test
    void canBeCancelledOnlyBeforeDispatch() {
        Shipment cancelled = created().cancel(NOW);

        assertThat(cancelled.status()).isEqualTo(Shipment.Status.CANCELLED);
        assertThat(cancelled.cancel(NOW)).isSameAs(cancelled);
        assertThatThrownBy(() -> cancelled.dispatch("TRK-1", NOW)).isInstanceOf(IllegalShipmentTransitionException.class);
        assertThatThrownBy(() -> created().dispatch("TRK-1", NOW).cancel(NOW))
                .isInstanceOf(IllegalShipmentTransitionException.class);
    }
}
