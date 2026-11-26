package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Return;
import io.github.ffetih.ecommerce.order.domain.ReturnLine;
import io.github.ffetih.ecommerce.order.domain.Sku;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Return row with its lines. Versioned: a back-office double click on "received", or a receive and a
 * reject at the same moment, cannot both commit.
 */
@Entity
@Table(name = "returns")
class ReturnEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Return.Status status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(length = 500)
    private String rejectionReason;

    @Column(nullable = false)
    private UUID idempotencyKey;

    @Column(nullable = false, length = 64)
    private String requestHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    // Lines are written once, when the return is requested, and never change.
    @ElementCollection
    @CollectionTable(name = "return_lines", joinColumns = @JoinColumn(name = "return_id"))
    @OrderColumn(name = "line_number")
    @ListIndexBase(1)
    private List<ReturnLineEmbeddable> lines = new ArrayList<>();

    protected ReturnEntity() {
    }

    ReturnEntity(Return value, UUID idempotencyKey, String requestHash) {
        this.id = value.id();
        this.orderId = value.orderId();
        this.customerId = value.customerId();
        this.refundAmount = value.refundAmount().amount();
        this.currency = value.refundAmount().currency().getCurrencyCode();
        this.reason = value.reason();
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = value.createdAt();
        value.lines().forEach(line -> lines.add(
                new ReturnLineEmbeddable(line.sku().value(), line.quantity(), line.unitPrice().amount())));
        update(value);
    }

    void update(Return value) {
        this.status = value.status();
        this.rejectionReason = value.rejectionReason();
        this.updatedAt = value.updatedAt();
    }

    String requestHash() {
        return requestHash;
    }

    Return toDomain() {
        Currency returnCurrency = Currency.getInstance(currency);
        List<ReturnLine> domainLines = lines.stream()
                .map(line -> new ReturnLine(Sku.of(line.sku()), line.quantity(), new Money(line.unitPrice(), returnCurrency)))
                .toList();
        return new Return(id, orderId, customerId, domainLines, new Money(refundAmount, returnCurrency), status,
                reason, rejectionReason, createdAt, updatedAt);
    }
}
