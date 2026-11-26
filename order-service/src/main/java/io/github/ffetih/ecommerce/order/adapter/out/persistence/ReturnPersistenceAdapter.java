package io.github.ffetih.ecommerce.order.adapter.out.persistence;

import io.github.ffetih.ecommerce.order.application.DuplicateIdempotencyKeyException;
import io.github.ffetih.ecommerce.order.application.ReturnNotFoundException;
import io.github.ffetih.ecommerce.order.application.port.ReturnRepository;
import io.github.ffetih.ecommerce.order.domain.Return;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mapping reads lazy line collections, so callers run these inside a transaction (ADR-0004). */
@Component
class ReturnPersistenceAdapter implements ReturnRepository {

    private static final String IDEMPOTENCY_CONSTRAINT = "returns_idempotency_key_unique";

    private final ReturnJpaRepository repository;

    ReturnPersistenceAdapter(ReturnJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Return> findById(UUID returnId) {
        return repository.findById(returnId).map(ReturnEntity::toDomain);
    }

    @Override
    public List<Return> findByOrderId(UUID orderId) {
        return repository.findByOrderIdOrderByCreatedAt(orderId).stream().map(ReturnEntity::toDomain).toList();
    }

    @Override
    public Optional<StoredReturn> findByIdempotencyKey(UUID orderId, UUID idempotencyKey) {
        return repository.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey)
                .map(entity -> new StoredReturn(entity.toDomain(), entity.requestHash()));
    }

    /** Flushes immediately so a duplicate key surfaces here, as an application exception, not at commit. */
    @Override
    public void insert(Return value, UUID idempotencyKey, String requestHash) {
        try {
            repository.saveAndFlush(new ReturnEntity(value, idempotencyKey, requestHash));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(IDEMPOTENCY_CONSTRAINT)) {
                throw new DuplicateIdempotencyKeyException(e);
            }
            throw e;
        }
    }

    @Override
    public void update(Return value) {
        ReturnEntity entity = repository.findById(value.id()).orElseThrow(() -> new ReturnNotFoundException(value.id()));
        entity.update(value);
        // Flush now: a concurrent change fails here, before anything else of this transaction is decided.
        repository.flush();
    }
}
