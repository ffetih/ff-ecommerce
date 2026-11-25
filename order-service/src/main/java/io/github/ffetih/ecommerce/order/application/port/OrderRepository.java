package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    /** An order together with the fingerprint of the request that created it. */
    record StoredOrder(Order order, String requestHash) {
    }

    Optional<Order> findById(UUID orderId);

    List<Order> findByCustomer(UUID customerId);

    Optional<StoredOrder> findByIdempotencyKey(UUID customerId, UUID idempotencyKey);

    /**
     * Inserts a new order.
     *
     * @throws io.github.ffetih.ecommerce.order.application.DuplicateIdempotencyKeyException if a concurrent
     *         request with the same customer and key was stored first
     */
    void insert(Order order, UUID idempotencyKey, String requestHash);

    void update(Order order);
}
