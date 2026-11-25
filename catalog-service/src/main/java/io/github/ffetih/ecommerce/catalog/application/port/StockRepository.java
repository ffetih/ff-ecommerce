package io.github.ffetih.ecommerce.catalog.application.port;

import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface StockRepository {

    Optional<StockItem> findBySku(Sku sku);

    /**
     * Locks the stock rows ({@code SELECT ... FOR UPDATE}) in SKU order and returns them by SKU.
     * Unknown SKUs are absent from the result. Must be called inside a transaction.
     */
    Map<Sku, StockItem> lockAll(Collection<Sku> skus);

    void save(StockItem item);
}
