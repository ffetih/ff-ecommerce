package io.github.ffetih.ecommerce.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Payment in the order flow (ADR-0009), observed through the public APIs only. The card token picks
 * the simulated provider's answer, so every outcome can be produced on purpose.
 */
class PaymentE2EIT {

    private static Api catalog;
    private static Api orders;
    private static Api shipping;

    @BeforeAll
    static void startPlatform() throws Exception {
        Platform platform = Platform.shared();
        String admin = platform.tokens.user("admin");
        String bob = platform.tokens.user("bob");
        catalog = new Api(platform.gateway.baseUrl(), admin);
        orders = new Api(platform.gateway.baseUrl(), bob);
        shipping = new Api(platform.gateway.baseUrl(), admin);
        Shop.registered(orders, "Bob Baker");
    }

    @Test
    void theOrderIsConfirmedOnceThePaymentIsAuthorizedAndTheMoneyIsTakenAtDispatch() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);
        String order = Shop.placeOrder(orders, sku, 1).text("id");

        Shop.awaitOrderStatus(orders, order, "CONFIRMED");
        assertThat(payment(order)).isEqualTo("AUTHORIZED");

        shipping.post("/api/v1/shipments/" + shipment(order) + "/dispatch", null);

        Shop.awaitPaymentStatus(orders, order, "CAPTURED");
        assertThat(status(order)).isEqualTo("SHIPPED");
    }

    @Test
    void aDeclinedCardCancelsTheOrderAndReleasesTheStock() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);

        String order = Shop.placeOrder(orders, sku, 2, "tok_decline").text("id");

        Shop.awaitOrderStatus(orders, order, "CANCELLED");
        assertThat(orders.get("/api/v1/orders/" + order).text("cancellationReason")).isEqualTo("Payment declined: Card declined");
        assertThat(payment(order)).isEqualTo("DECLINED");
        Shop.awaitUntil(() -> Shop.stock(catalog, sku, "reserved") == 0 && Shop.stock(catalog, sku, "available") == 5);
        assertThat(shipping.get("/api/v1/shipments?orderId=" + order).status()).isEqualTo(404);
    }

    @Test
    void aProviderThatDoesNotAnswerDelaysTheOrderButDoesNotDeclineIt() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);

        // The provider places the hold but its answer is lost; payment-service asks again with the same key.
        String order = Shop.placeOrder(orders, sku, 1, "tok_slow").text("id");

        Shop.awaitOrderStatus(orders, order, "CONFIRMED");
        assertThat(payment(order)).isEqualTo("AUTHORIZED");
    }

    @Test
    void cancellingAConfirmedOrderReleasesTheHold() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);
        String order = Shop.placeOrder(orders, sku, 1).text("id");
        Shop.awaitOrderStatus(orders, order, "CONFIRMED");
        shipment(order); // shipping has prepared the parcel, so the cancellation goes through shipping

        assertThat(orders.post("/api/v1/orders/" + order + "/cancel", "{\"reason\":\"changed my mind\"}").status())
                .isEqualTo(202);

        Shop.awaitOrderStatus(orders, order, "CANCELLED");
        Shop.awaitPaymentStatus(orders, order, "VOIDED");
    }

    @Test
    void aCaptureThatFailsAfterDispatchLeavesTheOrderShippedWithTheMoneyOwed() throws Exception {
        String sku = Shop.productWithStock(catalog, 5);
        String order = Shop.placeOrder(orders, sku, 1, "tok_capture_fails").text("id");
        Shop.awaitOrderStatus(orders, order, "CONFIRMED");

        shipping.post("/api/v1/shipments/" + shipment(order) + "/dispatch", null);

        Shop.awaitPaymentStatus(orders, order, "OVERDUE");
        assertThat(status(order)).isEqualTo("SHIPPED");
    }

    private static String shipment(String order) throws InterruptedException {
        return Shop.awaitValue(() -> shipping.get("/api/v1/shipments?orderId=" + order), r -> r.status() == 200).text("id");
    }

    private static String status(String order) {
        return orders.get("/api/v1/orders/" + order).text("status");
    }

    private static String payment(String order) {
        return orders.get("/api/v1/orders/" + order).text("paymentStatus");
    }
}
