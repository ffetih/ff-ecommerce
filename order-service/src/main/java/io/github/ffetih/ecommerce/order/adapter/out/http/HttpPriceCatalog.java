package io.github.ffetih.ecommerce.order.adapter.out.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.ffetih.ecommerce.order.application.DownstreamUnavailableException;
import io.github.ffetih.ecommerce.order.application.port.PriceCatalog;
import io.github.ffetih.ecommerce.order.config.ClientProperties;
import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Sku;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads current prices from catalog-service in one call for all SKUs of an order. */
@Component
class HttpPriceCatalog implements PriceCatalog {

    private static final ParameterizedTypeReference<List<ProductDto>> PRODUCTS = new ParameterizedTypeReference<>() {
    };

    private final RestClient rest;

    HttpPriceCatalog(ClientProperties properties) {
        this.rest = RestClients.create(properties.catalog());
    }

    @Override
    public Map<Sku, ProductPrice> currentPrices(Collection<Sku> skus) {
        Object[] skuValues = skus.stream().map(Sku::value).toArray();
        List<ProductDto> products;
        try {
            products = rest.get()
                    .uri(uri -> uri.path("/api/v1/products").queryParam("sku", skuValues).build())
                    .retrieve()
                    .body(PRODUCTS);
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("catalog-service rejected our request: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("catalog-service is unavailable", e);
        }
        Map<Sku, ProductPrice> prices = new LinkedHashMap<>();
        for (ProductDto product : products == null ? List.<ProductDto>of() : products) {
            Sku sku = Sku.of(product.sku());
            prices.put(sku, new ProductPrice(
                    sku, new Money(product.unitPrice(), Currency.getInstance(product.currency())), product.active()));
        }
        return prices;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductDto(String sku, BigDecimal unitPrice, String currency, boolean active) {
    }
}
