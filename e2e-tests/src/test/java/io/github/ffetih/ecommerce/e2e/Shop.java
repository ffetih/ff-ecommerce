package io.github.ffetih.ecommerce.e2e;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Steps and waits that several end-to-end tests share. */
final class Shop {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private Shop() {
    }

    /**
     * Registers the profile of the user behind the API's token, unless an earlier test already did
     * (201 or 409), and returns the customer id, which is that user's Keycloak id.
     */
    static String registered(Api customers, String fullName) {
        int status = customers.post("/api/v1/customers/me",
                "{\"fullName\":\"" + fullName + "\",\"shippingAddress\":\"Bağdat Cd. 1, İstanbul\"}").status();
        assertThat(status).isIn(201, 409);
        Api.Response me = customers.get("/api/v1/customers/me");
        assertThat(me.status()).isEqualTo(200);
        return me.text("id");
    }

    /** A new product with the given stock; {@code catalog} must carry an admin token. */
    static String productWithStock(Api catalog, int quantity) {
        String sku = "E2E-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        assertThat(catalog.post("/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"E2E product\",\"unitPrice\":19.99,\"currency\":\"EUR\"}").status())
                .isEqualTo(201);
        assertThat(catalog.post("/api/v1/stock/" + sku + "/receipts",
                "{\"referenceId\":\"" + UUID.randomUUID() + "\",\"quantity\":" + quantity + "}").status())
                .isEqualTo(200);
        return sku;
    }

    /** Card token the simulated payment provider approves. */
    static final String CARD_OK = "tok_ok";

    /** Places an order for one SKU as the user behind {@code orders}, paid with a card that is approved. */
    static Api.Response placeOrder(Api orders, String sku, int quantity) {
        return placeOrder(orders, sku, quantity, CARD_OK);
    }

    /**
     * Places an order for one SKU as the user behind {@code orders}; asserts it was accepted. The card
     * token picks the simulated provider's answer: tok_ok, tok_decline, tok_slow, tok_capture_fails.
     */
    static Api.Response placeOrder(Api orders, String sku, int quantity, String cardToken) {
        Api.Response placed = orders.post("/api/v1/orders", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"cardToken\":\"" + cardToken + "\",\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}]}");
        assertThat(placed.status()).isEqualTo(201);
        assertThat(placed.text("status")).isEqualTo("PLACED");
        return placed;
    }

    static int stock(Api catalog, String sku, String field) {
        return catalog.get("/api/v1/stock/" + sku).body().get(field).asInt();
    }

    static void awaitOrderStatus(Api orders, String order, String status) throws InterruptedException {
        awaitUntil(() -> status.equals(orders.get("/api/v1/orders/" + order).text("status")));
    }

    static void awaitPaymentStatus(Api orders, String order, String paymentStatus) throws InterruptedException {
        awaitUntil(() -> paymentStatus.equals(orders.get("/api/v1/orders/" + order).text("paymentStatus")));
    }

    /** Repeats the call until the answer is ready; fails with the last answer after 30 seconds. */
    static Api.Response awaitValue(Supplier<Api.Response> call, Predicate<Api.Response> ready)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        Api.Response last = call.get();
        while (!ready.test(last)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Not ready within " + PATIENCE.toSeconds() + "s; last response: " + last);
            }
            Thread.sleep(250);
            last = call.get();
        }
        return last;
    }

    static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(PATIENCE);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + PATIENCE.toSeconds() + "s");
            }
            Thread.sleep(250);
        }
    }
}
