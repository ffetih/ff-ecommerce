package io.github.ffetih.ecommerce.catalog.adapter.out.persistence;

import io.github.ffetih.ecommerce.catalog.application.ConflictException;
import io.github.ffetih.ecommerce.catalog.application.NotFoundException;
import io.github.ffetih.ecommerce.catalog.application.port.ProductRepository;
import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
class ProductPersistenceAdapter implements ProductRepository {

    private static final String PRIMARY_KEY = "products_pkey";

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

    /** Flushes at once, so a concurrent duplicate surfaces here as a conflict, not at commit. */
    @Override
    public Product insert(Product product) {
        try {
            return repository.saveAndFlush(new ProductEntity(product, clock.instant())).toDomain();
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(PRIMARY_KEY)) {
                throw ConflictException.productExists(product.sku());
            }
            throw e;
        }
    }

    @Override
    public Product update(Product product) {
        ProductEntity entity = repository.findById(product.sku().value())
                .orElseThrow(() -> NotFoundException.product(product.sku()));
        entity.update(product, clock.instant());
        return entity.toDomain();
    }
}
