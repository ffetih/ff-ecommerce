package io.github.ffetih.ecommerce.order.application.port;

import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Sku;

import java.util.Collection;
import java.util.Map;

/** Current prices, read from catalog-service at the moment an order is placed. */
public interface PriceCatalog {

    record ProductPrice(Sku sku, Money unitPrice, boolean active) {
    }

    /**
     * @return prices of the requested SKUs that exist; unknown SKUs are absent from the map
     * @throws io.github.ffetih.ecommerce.order.application.DownstreamUnavailableException if it cannot be asked
     */
    Map<Sku, ProductPrice> currentPrices(Collection<Sku> skus);
}
