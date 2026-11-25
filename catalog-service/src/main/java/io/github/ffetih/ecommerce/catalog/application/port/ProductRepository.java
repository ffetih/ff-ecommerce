package io.github.ffetih.ecommerce.catalog.application.port;

import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {

    Optional<Product> findBySku(Sku sku);

    List<Product> findAllBySku(Collection<Sku> skus);

    List<Product> findAll();

    /** Stores a new product; fails with {@code ConflictException} if the SKU exists, never overwrites. */
    Product insert(Product product);

    /** Stores changes to an existing product. */
    Product update(Product product);
}
