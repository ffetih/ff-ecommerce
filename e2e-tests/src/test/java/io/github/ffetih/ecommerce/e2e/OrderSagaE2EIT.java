package io.github.ffetih.ecommerce.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order saga across all services (ADR-0007), observed only through their public HTTP APIs.
 * Each test uses its own product and customer, so the tests do not depend on each other.
 */
class OrderSagaE2EIT {

    private static Platform platform;
    private static Api catalog;
    private static Api customers;
    private static Api orders;
    private static Api shipping;

    @BeforeAll
    static void startPlatform() throws Exception {
        platform = Platform.shared();
        // Stocking the catalog and moving shipments along are back-office work.
        String admin = platform.tokens.user("admin");
        String alice = platform.tokens.user("alice");
        catalog = new Api(platform.gateway.baseUrl(), admin);
        customers = new Api(platform.gateway.baseUrl(), alice);
        orders = new Api(platform.gateway.baseUrl(), alice);
        shipping = new Api(platform.gateway.baseUrl(), admin);
    }

    @Test
    void anOrderIsConfirmedShippedAndDeliveredAndTheStockFollows() throws Exception {
        String sku = productWithStock(5);
        String customer = customer();

        String order = placeOrder(customer, sku, 2);
        awaitOrderStatus(order, "CONFIRMED");
        assertThat(stock(sku, "reserved")).isEqualTo(2);

        String shipment = awaitValue(() -> shipping.get("/api/v1/shipments?orderId=" + order), r -> r.status() == 200).text("id");
        assertThat(shipping.post("/api/v1/shipments/" + shipment + "/dispatch", null).status()).isEqualTo(200);
        awaitOrderStatus(order, "SHIPPED");
        awaitUntil(() -> stock(sku, "onHand") == 3 && stock(sku, "reserved") == 0);

        assertThat(shipping.post("/api/v1/shipments/" + shipment + "/deliver", null).status()).isEqualTo(200);
        awaitOrderStatus(order, "DELIVERED");
    }

    @Test
    void anOrderForMoreThanTheStockIsCancelledAndHoldsNothing() throws Exception {
        String sku = productWithStock(1);
        String customer = customer();

        String order = placeOrder(customer, sku, 3);

        awaitOrderStatus(order, "CANCELLED");
        assertThat(orders.get("/api/v1/orders/" + order).text("cancellationReason")).contains("Insufficient stock");
        assertThat(stock(sku, "reserved")).isZero();
        assertThat(shipping.get("/api/v1/shipments?orderId=" + order).status()).isEqualTo(404);
    }

    @Test
    void aCustomerCancellationReleasesTheStockAndStopsTheShipment() throws Exception {
        String sku = productWithStock(5);
        String customer = customer();
        String order = placeOrder(customer, sku, 2);
        awaitOrderStatus(order, "CONFIRMED");
        awaitValue(() -> shipping.get("/api/v1/shipments?orderId=" + order), r -> r.status() == 200);

        Api.Response requested = orders.post("/api/v1/orders/" + order + "/cancel", "{\"reason\":\"changed my mind\"}");

        // Confirmed: shipping decides whether the parcel can still be stopped (ADR-0007).
        assertThat(requested.status()).isEqualTo(202);
        assertThat(requested.text("status")).isEqualTo("CANCELLATION_REQUESTED");
        awaitOrderStatus(order, "CANCELLED");
        awaitUntil(() -> stock(sku, "reserved") == 0 && stock(sku, "available") == 5);
        awaitUntil(() -> "CANCELLED".equals(shipping.get("/api/v1/shipments?orderId=" + order).text("status")));
    }

    // --- steps (shared with the other end-to-end tests in Shop) -------------------------------

    private static String productWithStock(int quantity) {
        return Shop.productWithStock(catalog, quantity);
    }

    /** Alice's customer profile; registered on first use (the platform is shared between test classes). */
    private static String customer() {
        return Shop.registered(customers, "Alice Archer");
    }

    private static String placeOrder(String customer, String sku, int quantity) {
        Api.Response placed = Shop.placeOrder(orders, sku, quantity);
        assertThat(placed.text("customerId")).isEqualTo(customer);
        return placed.text("id");
    }

    private static int stock(String sku, String field) {
        return Shop.stock(catalog, sku, field);
    }

    private static void awaitOrderStatus(String order, String status) throws InterruptedException {
        Shop.awaitOrderStatus(orders, order, status);
    }

    private static Api.Response awaitValue(Supplier<Api.Response> call, Predicate<Api.Response> ready)
            throws InterruptedException {
        return Shop.awaitValue(call, ready);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        Shop.awaitUntil(condition);
    }
}
