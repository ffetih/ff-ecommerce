package io.github.ffetih.ecommerce.shipping.adapter.out.persistence;

import io.github.ffetih.ecommerce.shipping.domain.Shipment;
import io.github.ffetih.ecommerce.shipping.domain.ShipmentLine;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ListIndexBase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Optimistic locking: a warehouse dispatch and an order cancellation racing on one shipment must not both win. */
@Entity
@Table(name = "shipments")
class ShipmentEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 500)
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Shipment.Status status;

    @Column(length = 32)
    private String trackingNumber;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @ElementCollection
    @CollectionTable(name = "shipment_lines", joinColumns = @JoinColumn(name = "shipment_id"))
    @OrderColumn(name = "line_number")
    @ListIndexBase(1)
    private List<ShipmentLineEmbeddable> lines = new ArrayList<>();

    protected ShipmentEntity() {
    }

    ShipmentEntity(Shipment shipment) {
        this.id = shipment.id();
        this.orderId = shipment.orderId();
        this.customerId = shipment.customerId();
        this.shippingAddress = shipment.shippingAddress();
        this.createdAt = shipment.createdAt();
        shipment.lines().forEach(line -> lines.add(new ShipmentLineEmbeddable(line.sku(), line.quantity())));
        update(shipment);
    }

    void update(Shipment shipment) {
        this.status = shipment.status();
        this.trackingNumber = shipment.trackingNumber();
        this.updatedAt = shipment.updatedAt();
    }

    Shipment toDomain() {
        return new Shipment(id, orderId, customerId,
                lines.stream().map(line -> new ShipmentLine(line.sku(), line.quantity())).toList(),
                shippingAddress, status, trackingNumber, createdAt, updatedAt);
    }
}
