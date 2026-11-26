package io.github.ffetih.ecommerce.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns across all services (ADR-0010), through the gateway only: order-service counts and starts,
 * catalog restocks, payment refunds.
 */
class ReturnE2EIT {

    private static Api catalog;
    private static Api orders;
    private static Api backOffice;

    @BeforeAll
    static void startPlatform() throws Exception {
        Platform platform = Platform.shared();
        String admin = platform.tokens.user("admin");
        catalog = new Api(platform.gateway.baseUrl(), admin);
        backOffice = new Api(platform.gateway.baseUrl(), admin);
        orders = new Api(platform.gateway.baseUrl(), platform.tokens.user("bob"));
        Shop.registered(orders, "Bob Baker");
    }

    @Test
    void aReturnedUnitIsRestockedAndRefunded() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);
        String order = deliveredOrder(sku, 2, Shop.CARD_OK);
        Shop.awaitUntil(() -> Shop.stock(catalog, sku, "onHand") == 3); // catalog commits on dispatch

        Api.Response requested = requestReturn(order, sku, 1);

        assertThat(requested.status()).isEqualTo(201);
        assertThat(requested.text("status")).isEqualTo("REQUESTED");
        assertThat(requested.body().get("refundAmount").decimalValue()).isEqualByComparingTo("19.99");

        String returnId = requested.text("id");
        assertThat(backOffice.post("/api/v1/returns/" + returnId + "/receive", null).text("status")).isEqualTo("RECEIVED");

        awaitReturnStatus(returnId, "REFUNDED");
        Shop.awaitUntil(() -> Shop.stock(catalog, sku, "onHand") == 4);
        assertThat(Shop.stock(catalog, sku, "available")).isEqualTo(4);
    }

    /** Two units bought, six requests for one unit each at the same moment: exactly two succeed. */
    @Test
    void concurrentReturnsNeverExceedWhatWasBought() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);
        String order = deliveredOrder(sku, 2, Shop.CARD_OK);
        var statuses = new ConcurrentLinkedQueue<Integer>();
        var start = new CountDownLatch(1);

        try (var customers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 6; i++) {
                customers.submit(() -> {
                    start.await();
                    statuses.add(requestReturn(order, sku, 1).status());
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(2);
        assertThat(statuses).filteredOn(status -> status == 422).hasSize(4);
        assertThat(orders.get("/api/v1/orders/" + order + "/returns").body().get("returnedUnits").get(sku).asInt())
                .isEqualTo(2);
    }

    @Test
    void aRefundTheProviderRefusesLeavesTheReturnForOperationsButTheUnitsAreBack() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);
        String order = deliveredOrder(sku, 1, "tok_refund_fails");
        String returnId = requestReturn(order, sku, 1).text("id");

        backOffice.post("/api/v1/returns/" + returnId + "/receive", null);

        awaitReturnStatus(returnId, "REFUND_FAILED");
        Shop.awaitUntil(() -> Shop.stock(catalog, sku, "onHand") == 5);
    }

    // --- steps -------------------------------------------------------------------------------

    /** Placed, paid, dispatched and delivered; the money captured. */
    private static String deliveredOrder(String sku, int quantity, String cardToken) throws Exception {
        String order = Shop.placeOrder(orders, sku, quantity, cardToken).text("id");
        Shop.awaitOrderStatus(orders, order, "CONFIRMED");
        String shipment = Shop.awaitValue(() -> backOffice.get("/api/v1/shipments?orderId=" + order),
                response -> response.status() == 200).text("id");
        assertThat(backOffice.post("/api/v1/shipments/" + shipment + "/dispatch", null).status()).isEqualTo(200);
        Shop.awaitPaymentStatus(orders, order, "CAPTURED");
        assertThat(backOffice.post("/api/v1/shipments/" + shipment + "/deliver", null).status()).isEqualTo(200);
        Shop.awaitOrderStatus(orders, order, "DELIVERED");
        return order;
    }

    private static Api.Response requestReturn(String order, String sku, int quantity) {
        return orders.post("/api/v1/orders/" + order + "/returns", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}],\"reason\":\"does not fit\"}");
    }

    private static void awaitReturnStatus(String returnId, String status) throws InterruptedException {
        Shop.awaitUntil(() -> status.equals(orders.get("/api/v1/returns/" + returnId).text("status")));
    }
}
