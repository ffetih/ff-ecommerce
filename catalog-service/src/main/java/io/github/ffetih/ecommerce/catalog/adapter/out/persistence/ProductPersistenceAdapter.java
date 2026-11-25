package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
class ProductPersistenceAdapter implements ProductRepository {

    private final ProductJpaRepository repository;
    private final Clock clock;

    ProductPersistenceAdapter(ProductJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<Product> findBySku(Sku sku) {
        return repository.findById(sku.value()).map(ProductEntity::toDomain);
    }

    @Override
    public List<Product> findAllBySku(Collection<Sku> skus) {
        return repository.findAllBySkuInOrderBySku(skus.stream().map(Sku::value).toList()).stream()
                .map(ProductEntity::toDomain)
                .toList();
    }

    @Override
    public List<Product> findAll() {
        return repository.findAll(Sort.by("sku")).stream().map(ProductEntity::toDomain).toList();
    }

    @Override
    public Product save(Product product) {
        var now = clock.instant();
        ProductEntity entity = repository.findById(product.sku().value())
                .map(existing -> {
                    existing.update(product, now);
                    return existing;
                })
                .orElseGet(() -> new ProductEntity(product, now));
        return repository.save(entity).toDomain();
    }
}
