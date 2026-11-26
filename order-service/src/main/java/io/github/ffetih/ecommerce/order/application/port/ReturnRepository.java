package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Return;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReturnRepository {

    /** A stored return together with the fingerprint of the request that created it. */
    record StoredReturn(Return value, String requestHash) {
    }

    Optional<Return> findById(UUID returnId);

    List<Return> findByOrderId(UUID orderId);

    Optional<StoredReturn> findByIdempotencyKey(UUID orderId, UUID idempotencyKey);

    /** @throws io.github.ffetih.ecommerce.order.application.DuplicateIdempotencyKeyException if the key was stored first by someone else */
    void insert(Return value, UUID idempotencyKey, String requestHash);

    /** Optimistic: fails if someone else changed the return since it was read. */
    void update(Return value);
}
