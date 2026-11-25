package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.domain.Sku;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retries are normal in distributed systems: the same request must have the same effect as one request. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyIT {

    @Autowired
    ProductService products;

    @Autowired
    StockService stock;

    @Autowired
    ReservationService reservations;

    @Test
    void concurrentRetriesOfOneReservationHoldStockOnlyOnce() {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();

        var outcome = Concurrently.run(10, i -> reservations.reserve(orderId, List.of(line(sku, 2))));

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.succeeded()).isEqualTo(10);
        assertThat(stock.get(sku).reserved()).isEqualTo(2);
    }

    @Test
    void reusingAnOrderIdForDifferentLinesIsRejected() {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(orderId, List.of(line(sku, 2)));

        assertThatThrownBy(() -> reservations.reserve(orderId, List.of(line(sku, 3))))
                .isInstanceOf(ConflictException.class);
        assertThat(stock.get(sku).reserved()).isEqualTo(2);
    }

    @Test
    void concurrentRetriesOfOneGoodsReceiptAddStockOnlyOnce() {
        Sku sku = productWithStock(products, stock, 0);
        UUID referenceId = UUID.randomUUID();

        var outcome = Concurrently.run(10, i -> stock.receive(referenceId, sku, 5));

        assertThat(outcome.failures()).isEmpty();
        assertThat(stock.get(sku).onHand()).isEqualTo(5);
    }

    @Test
    void reusingAReceiptReferenceForADifferentQuantityIsRejected() {
        Sku sku = productWithStock(products, stock, 0);
        UUID referenceId = UUID.randomUUID();
        stock.receive(referenceId, sku, 5);

        assertThatThrownBy(() -> stock.receive(referenceId, sku, 7)).isInstanceOf(ConflictException.class);
        assertThat(stock.get(sku).onHand()).isEqualTo(5);
    }
}
