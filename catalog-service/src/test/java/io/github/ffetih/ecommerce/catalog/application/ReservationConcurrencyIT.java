package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.domain.InsufficientStockException;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
import io.github.ffetih.ecommerce.catalog.support.Concurrently;
import io.github.ffetih.ecommerce.catalog.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static io.github.ffetih.ecommerce.catalog.support.TestData.line;
import static io.github.ffetih.ecommerce.catalog.support.TestData.productWithStock;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationConcurrencyIT {

    @Autowired
    ProductService products;

    @Autowired
    StockService stock;

    @Autowired
    ReservationService reservations;

    @Test
    void neverOversellsWhenManyOrdersCompeteForTheLastUnits() {
        Sku sku = productWithStock(products, stock, 10);

        var outcome = Concurrently.run(50, i ->
                reservations.reserve(UUID.randomUUID(), List.of(line(sku, 1))));

        assertThat(outcome.succeeded()).isEqualTo(10);
        assertThat(outcome.failures()).hasSize(40).allMatch(InsufficientStockException.class::isInstance);

        StockItem after = stock.get(sku);
        assertThat(after.onHand()).isEqualTo(10);
        assertThat(after.reserved()).isEqualTo(10);
        assertThat(after.available()).isZero();
    }

    @Test
    void ordersListingSharedSkusInOppositeOrderDoNotDeadlock() {
        Sku keyboard = productWithStock(products, stock, 100);
        Sku mouse = productWithStock(products, stock, 100);

        // Half the orders list keyboard first, half mouse first. Without a fixed lock order,
        // PostgreSQL would detect deadlocks and abort some of these transactions.
        var outcome = Concurrently.run(40, i -> reservations.reserve(UUID.randomUUID(),
                i % 2 == 0
                        ? List.of(line(keyboard, 1), line(mouse, 1))
                        : List.of(line(mouse, 1), line(keyboard, 1))));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(40);
        assertThat(stock.get(keyboard).reserved()).isEqualTo(40);
        assertThat(stock.get(mouse).reserved()).isEqualTo(40);
    }
}
