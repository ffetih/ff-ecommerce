package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

@Embeddable
class ReturnLineEmbeddable {

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected ReturnLineEmbeddable() {
    }

    ReturnLineEmbeddable(String sku, int quantity, BigDecimal unitPrice) {
        this.sku = sku;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    String sku() {
        return sku;
    }

    int quantity() {
        return quantity;
    }

    BigDecimal unitPrice() {
        return unitPrice;
    }
}
