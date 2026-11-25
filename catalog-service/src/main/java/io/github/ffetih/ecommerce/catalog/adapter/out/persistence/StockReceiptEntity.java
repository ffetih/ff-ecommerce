package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockReceipt;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_receipts")
class StockReceiptEntity {

    @Id
    private UUID referenceId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private Instant receivedAt;

    protected StockReceiptEntity() {
    }

    StockReceiptEntity(StockReceipt receipt) {
        this.referenceId = receipt.referenceId();
        this.sku = receipt.sku().value();
        this.quantity = receipt.quantity();
        this.receivedAt = receipt.receivedAt();
    }

    StockReceipt toDomain() {
        return new StockReceipt(referenceId, Sku.of(sku), quantity, receivedAt);
    }
}
