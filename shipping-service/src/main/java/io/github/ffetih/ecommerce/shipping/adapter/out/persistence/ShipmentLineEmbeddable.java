package io.github.ffetih.ecommerce.shipping.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class ShipmentLineEmbeddable {

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    protected ShipmentLineEmbeddable() {
    }

    ShipmentLineEmbeddable(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    String sku() {
        return sku;
    }

    int quantity() {
        return quantity;
    }
}
