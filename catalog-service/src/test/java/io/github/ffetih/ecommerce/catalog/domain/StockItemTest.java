package io.github.ffetih.ecommerce.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockItemTest {

    private static final Sku SKU = Sku.of("KB-MECH-01");

    @Test
    void reservingKeepsUnitsOnHandButMakesThemUnavailable() {
        StockItem stock = StockItem.empty(SKU).receive(10).reserve(3);

        assertThat(stock.onHand()).isEqualTo(10);
        assertThat(stock.reserved()).isEqualTo(3);
        assertThat(stock.available()).isEqualTo(7);
    }

    @Test
    void rejectsReservingMoreThanAvailable() {
        StockItem stock = StockItem.empty(SKU).receive(5).reserve(4);

        assertThatThrownBy(() -> stock.reserve(2))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.requested()).isEqualTo(2);
                    assertThat(ex.available()).isEqualTo(1);
                });
    }

    @Test
    void releasingMakesUnitsAvailableAgain() {
        StockItem stock = StockItem.empty(SKU).receive(10).reserve(3).release(3);

        assertThat(stock.available()).isEqualTo(10);
        assertThat(stock.reserved()).isZero();
    }

    @Test
    void committingRemovesUnitsFromTheWarehouse() {
        StockItem stock = StockItem.empty(SKU).receive(10).reserve(3).commit(3);

        assertThat(stock.onHand()).isEqualTo(7);
        assertThat(stock.reserved()).isZero();
        assertThat(stock.available()).isEqualTo(7);
    }

    @Test
    void cannotReleaseOrCommitMoreThanIsReserved() {
        StockItem stock = StockItem.empty(SKU).receive(10).reserve(2);

        assertThatIllegalStateException().isThrownBy(() -> stock.release(3));
        assertThatIllegalStateException().isThrownBy(() -> stock.commit(3));
    }
}
