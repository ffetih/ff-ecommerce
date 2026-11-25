package io.github.ffetih.ecommerce.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
        catalog = new Api(platform.catalog.baseUrl(), admin);
        customers = new Api(platform.customer.baseUrl(), alice);
        orders = new Api(platform.order.baseUrl(), alice);
        shipping = new Api(platform.shipping.baseUrl(), admin);
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

        Api.Response cancelled = orders.post("/api/v1/orders/" + order + "/cancel", "{\"reason\":\"changed my mind\"}");

        assertThat(cancelled.status()).isEqualTo(200);
        awaitUntil(() -> stock(sku, "reserved") == 0 && stock(sku, "available") == 5);
        awaitUntil(() -> "CANCELLED".equals(shipping.get("/api/v1/shipments?orderId=" + order).text("status")));
    }

    // --- steps -------------------------------------------------------------------------------

    private static String productWithStock(int quantity) {
        String sku = "E2E-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        assertThat(catalog.post("/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"E2E product\",\"unitPrice\":19.99,\"currency\":\"EUR\"}").status())
                .isEqualTo(201);
        assertThat(catalog.post("/api/v1/stock/" + sku + "/receipts",
                "{\"referenceId\":\"" + UUID.randomUUID() + "\",\"quantity\":" + quantity + "}").status())
                .isEqualTo(200);
        return sku;
    }

    /** Alice's customer profile; registered on first use (the platform is shared between test classes). */
    private static String customer() {
        return Shop.registered(customers, "Alice Archer");
    }

    private static String placeOrder(String customer, String sku, int quantity) {
        Api.Response placed = orders.post("/api/v1/orders", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}]}");
        assertThat(placed.status()).isEqualTo(201);
        assertThat(placed.text("status")).isEqualTo("PLACED");
        assertThat(placed.text("customerId")).isEqualTo(customer);
        return placed.text("id");
    }

    private static int stock(String sku, String field) {
        return catalog.get("/api/v1/stock/" + sku).body().get(field).asInt();
    }

    private static void awaitOrderStatus(String order, String status) throws InterruptedException {
        awaitUntil(() -> status.equals(orders.get("/api/v1/orders/" + order).text("status")));
    }

    private static Api.Response awaitValue(Supplier<Api.Response> call,
                                           java.util.function.Predicate<Api.Response> ready) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        Api.Response last = call.get();
        while (!ready.test(last)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Not ready within 30s; last response: " + last);
            }
            Thread.sleep(250);
            last = call.get();
        }
        return last;
    }

    private static void awaitUntil(Supplier<Boolean> condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!condition.get()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within 30s");
            }
            Thread.sleep(250);
        }
    }
}
