package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.port.StockRepository;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
class StockPersistenceAdapter implements StockRepository {

    private final StockItemJpaRepository repository;
    private final Clock clock;

    StockPersistenceAdapter(StockItemJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<StockItem> findBySku(Sku sku) {
        return repository.findById(sku.value()).map(StockItemEntity::toDomain);
    }

    @Override
    public Map<Sku, StockItem> lockAll(Collection<Sku> skus) {
        Map<Sku, StockItem> locked = new LinkedHashMap<>();
        for (StockItemEntity entity : repository.lockAllBySkuIn(skus.stream().map(Sku::value).toList())) {
            StockItem item = entity.toDomain();
            locked.put(item.sku(), item);
        }
        return locked;
    }

    @Override
    public void save(StockItem item) {
        var now = clock.instant();
        // Locked rows are already managed in this transaction, so findById is served from the persistence context.
        StockItemEntity entity = repository.findById(item.sku().value())
                .map(existing -> {
                    existing.update(item, now);
                    return existing;
                })
                .orElseGet(() -> new StockItemEntity(item, now));
        repository.save(entity);
    }
}
