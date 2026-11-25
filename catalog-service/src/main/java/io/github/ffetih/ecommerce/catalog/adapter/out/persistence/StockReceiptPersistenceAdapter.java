package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.port.StockReceiptRepository;
import io.github.ffetih.ecommerce.catalog.domain.StockReceipt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
class StockReceiptPersistenceAdapter implements StockReceiptRepository {

    private final StockReceiptJpaRepository repository;

    StockReceiptPersistenceAdapter(StockReceiptJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StockReceipt> findById(UUID referenceId) {
        return repository.findById(referenceId).map(StockReceiptEntity::toDomain);
    }

    @Override
    public void save(StockReceipt receipt) {
        repository.save(new StockReceiptEntity(receipt));
    }
}
