package io.github.ffetih.ecommerce.catalog.support;

import io.github.ffetih.ecommerce.catalog.application.ProductService;
import io.github.ffetih.ecommerce.catalog.application.StockService;
import io.github.ffetih.ecommerce.catalog.domain.Money;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;

import java.util.UUID;

/** Every test works on its own random SKUs, so tests sharing one database never see each other's data. */
public final class TestData {

    private TestData() {
    }

    public static Sku uniqueSku(String prefix) {
        return Sku.of(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public static Sku productWithStock(ProductService products, StockService stock, int onHand) {
        Sku sku = uniqueSku("SKU");
        products.create(sku, "Test product " + sku, Money.of("10.00", "EUR"));
        if (onHand > 0) {
            stock.receive(UUID.randomUUID(), sku, onHand);
        }
        return sku;
    }

    public static Reservation.Line line(Sku sku, int quantity) {
        return new Reservation.Line(sku, quantity);
    }
}
