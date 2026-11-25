package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
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
 * Order row with its lines. Optimistic locking: status changes come from event consumers and from
 * customers (cancel), and a lost update between them must fail rather than be silently overwritten.
 */
@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Order.Status status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 500)
    private String shippingAddress;

    @Column(length = 500)
    private String cancellationReason;

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

    // Lines are written once at placement and never change.
    @ElementCollection
    @CollectionTable(name = "order_lines", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "line_number")
    @ListIndexBase(1)
    private List<OrderLineEmbeddable> lines = new ArrayList<>();

    protected OrderEntity() {
    }

    OrderEntity(Order order, UUID idempotencyKey, String requestHash) {
        this.id = order.id();
        this.customerId = order.customerId();
        this.currency = order.total().currency().getCurrencyCode();
        this.totalAmount = order.total().amount();
        this.shippingAddress = order.shippingAddress();
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = order.createdAt();
        order.lines().forEach(line -> lines.add(
                new OrderLineEmbeddable(line.sku().value(), line.quantity(), line.unitPrice().amount())));
        update(order);
    }

    void update(Order order) {
        this.status = order.status();
        this.cancellationReason = order.cancellationReason();
        this.updatedAt = order.updatedAt();
    }

    String requestHash() {
        return requestHash;
    }

    Order toDomain() {
        Currency orderCurrency = Currency.getInstance(currency);
        List<OrderLine> domainLines = lines.stream()
                .map(line -> new OrderLine(Sku.of(line.sku()), line.quantity(), new Money(line.unitPrice(), orderCurrency)))
                .toList();
        return new Order(id, customerId, domainLines, new Money(totalAmount, orderCurrency), shippingAddress,
                status, cancellationReason, createdAt, updatedAt);
    }
}
