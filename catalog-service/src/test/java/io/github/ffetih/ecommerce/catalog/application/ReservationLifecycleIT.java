package io.github.ffetih.ecommerce.catalog.application;

import io.github.ffetih.ecommerce.catalog.domain.InsufficientStockException;
import io.github.ffetih.ecommerce.catalog.domain.InvalidReservationStateException;
import io.github.ffetih.ecommerce.catalog.domain.Reservation;
import io.github.ffetih.ecommerce.catalog.domain.Sku;
import io.github.ffetih.ecommerce.catalog.domain.StockItem;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReservationLifecycleIT {

    @Autowired
    ProductService products;

    @Autowired
    StockService stock;

    @Autowired
    ReservationService reservations;

    @Test
    void commitShipsReservedUnitsOutOfTheWarehouseExactlyOnce() {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(orderId, List.of(line(sku, 3)));

        assertThat(reservations.commit(orderId).status()).isEqualTo(Reservation.Status.COMMITTED);
        reservations.commit(orderId); // retry: no further effect

        StockItem after = stock.get(sku);
        assertThat(after.onHand()).isEqualTo(7);
        assertThat(after.reserved()).isZero();
    }

    @Test
    void releaseReturnsUnitsAndBlocksALaterCommit() {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(orderId, List.of(line(sku, 3)));

        reservations.release(orderId);
        reservations.release(orderId); // retry: no further effect

        assertThat(stock.get(sku).available()).isEqualTo(10);
        assertThatThrownBy(() -> reservations.commit(orderId)).isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void aReleasedReservationIsNotReservedAgainUnderTheSameOrderId() {
        Sku sku = productWithStock(products, stock, 10);
        UUID orderId = UUID.randomUUID();
        reservations.reserve(orderId, List.of(line(sku, 3)));
        reservations.release(orderId);

        assertThatThrownBy(() -> reservations.reserve(orderId, List.of(line(sku, 3))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("released");
        assertThat(stock.get(sku).available()).isEqualTo(10);
        assertThat(reservations.get(orderId).status()).isEqualTo(Reservation.Status.RELEASED);
    }

    @Test
    void aShortLineFailsTheWholeReservationAndLeavesNothingBehind() {
        Sku plenty = productWithStock(products, stock, 10);
        Sku scarce = productWithStock(products, stock, 1);
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> reservations.reserve(orderId, List.of(line(plenty, 5), line(scarce, 2))))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(stock.get(plenty).reserved()).isZero();
        assertThat(stock.get(scarce).reserved()).isZero();
        assertThatThrownBy(() -> reservations.get(orderId)).isInstanceOf(NotFoundException.class);
    }
}
