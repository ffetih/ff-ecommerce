package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Stock row. No {@code @Version}: concurrent updates are serialized by pessimistic row locks
 * ({@link StockItemJpaRepository#lockAllBySkuIn}), so an optimistic version would add nothing.
 */
@Entity
@Table(name = "stock_items")
class StockItemEntity {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Column(nullable = false)
    private Instant updatedAt;

    protected StockItemEntity() {
    }

    StockItemEntity(StockItem item, Instant now) {
        this.sku = item.sku().value();
        update(item, now);
    }

    void update(StockItem item, Instant now) {
        this.onHand = item.onHand();
        this.reserved = item.reserved();
        this.updatedAt = now;
    }

    StockItem toDomain() {
        return new StockItem(Sku.of(sku), onHand, reserved);
    }
}
