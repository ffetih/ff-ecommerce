package io.github.ffetih.ecommerce.catalog.adapter.in.web;

import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.domain.Money;
import io.github.ffetih.ecommerce.catalog.domain.Product;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        Product product = products.create(
                Sku.of(request.sku()), request.name(), new Money(request.unitPrice(), currency(request.currency())));
        return ProductResponse.from(product);
    }

    @GetMapping("/{sku}")
    ProductResponse get(@PathVariable String sku) {
        return ProductResponse.from(products.get(Sku.of(sku)));
    }

    /** {@code GET /api/v1/products?sku=A&sku=B} returns those products; without {@code sku} it returns all. */
    @GetMapping
    List<ProductResponse> find(@RequestParam(name = "sku", required = false) List<String> skus) {
        List<Sku> requested = skus == null ? List.of() : skus.stream().map(Sku::of).toList();
        return products.find(requested).stream().map(ProductResponse::from).toList();
    }

    @PutMapping("/{sku}/price")
    ProductResponse changePrice(@PathVariable String sku, @Valid @RequestBody PriceRequest request) {
        return ProductResponse.from(
                products.changePrice(Sku.of(sku), new Money(request.unitPrice(), currency(request.currency()))));
    }

    private static java.util.Currency currency(String code) {
        return java.util.Currency.getInstance(code.trim().toUpperCase(java.util.Locale.ROOT));
    }

    record CreateProductRequest(
            @NotBlank String sku,
            @NotBlank String name,
            @NotNull @PositiveOrZero BigDecimal unitPrice,
            @NotBlank String currency) {
    }

    record PriceRequest(@NotNull @PositiveOrZero BigDecimal unitPrice, @NotBlank String currency) {
    }

    record ProductResponse(String sku, String name, BigDecimal unitPrice, String currency, boolean active) {

        static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.sku().value(),
                    product.name(),
                    product.unitPrice().amount(),
                    product.unitPrice().currency().getCurrencyCode(),
                    product.active());
        }
    }
}
