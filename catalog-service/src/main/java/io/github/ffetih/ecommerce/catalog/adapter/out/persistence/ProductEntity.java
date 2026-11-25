package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.domain.Money;
import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;

@Entity
@Table(name = "products")
class ProductEntity implements Persistable<String> {

    /**
     * True for an entity created here, false once stored or loaded, so a new product is INSERTed and
     * never merged into an existing one with the same SKU (see ProductPersistenceAdapter.insert).
     */
    @Transient
    private boolean isNew;

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ProductEntity() {
    }

    ProductEntity(Product product, Instant now) {
        this.isNew = true;
        this.sku = product.sku().value();
        this.createdAt = now;
        update(product, now);
    }

    @Override
    public String getId() {
        return sku;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void stored() {
        isNew = false;
    }

    void update(Product product, Instant now) {
        this.name = product.name();
        this.unitPrice = product.unitPrice().amount();
        this.currency = product.unitPrice().currency().getCurrencyCode();
        this.active = product.active();
        this.updatedAt = now;
    }

    Product toDomain() {
        return new Product(Sku.of(sku), name, new Money(unitPrice, Currency.getInstance(currency)), active);
    }
}
