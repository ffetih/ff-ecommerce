package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import io.github.ffetih.ecommerce.order.application.DuplicateIdempotencyKeyException;
import io.github.ffetih.ecommerce.order.application.OrderNotFoundException;
import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.domain.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class OrderPersistenceAdapter implements OrderRepository {

    private static final String IDEMPOTENCY_CONSTRAINT = "orders_idempotency_key_unique";

    private final OrderJpaRepository repository;

    OrderPersistenceAdapter(OrderJpaRepository repository) {
        this.repository = repository;
    }

    // Mapping reads lazy line collections, so callers run these inside a transaction (ADR-0004).
    @Override
    public Optional<Order> findById(UUID orderId) {
        return repository.findById(orderId).map(OrderEntity::toDomain);
    }

    @Override
    public List<Order> findByCustomer(UUID customerId) {
        return repository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream().map(OrderEntity::toDomain).toList();
    }

    @Override
    public Optional<StoredOrder> findByIdempotencyKey(UUID customerId, UUID idempotencyKey) {
        return repository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .map(entity -> new StoredOrder(entity.toDomain(), entity.requestHash()));
    }

    /** Flushes immediately so a duplicate key surfaces here, as an application exception, not at commit. */
    @Override
    public void insert(Order order, UUID idempotencyKey, String requestHash) {
        try {
            repository.saveAndFlush(new OrderEntity(order, idempotencyKey, requestHash));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(IDEMPOTENCY_CONSTRAINT)) {
                throw new DuplicateIdempotencyKeyException(e);
            }
            throw e;
        }
    }

    @Override
    public void update(Order order) {
        OrderEntity entity = repository.findById(order.id()).orElseThrow(() -> new OrderNotFoundException(order.id()));
        entity.update(order);
    }

    @Override
    public List<UUID> findStale(Instant placedBefore, Instant confirmedBefore, int limit) {
        return repository.findStaleIds(placedBefore, confirmedBefore, limit);
    }

    @Override
    public Optional<Order> lockIfFree(UUID orderId) {
        return repository.lockIfFree(orderId).map(OrderEntity::toDomain);
    }
}
