package io.github.ffetih.ecommerce.e2e;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Keycloak realm (infra/keycloak) and the services' rules agree: real tokens, from the real
 * realm, accepted or rejected by the packaged services. Integration tests already cover each rule
 * with tokens they sign themselves; this test proves the realm actually issues tokens of that shape
 * (audiences, roles).
 */
class AccessControlE2EIT {

    private static Platform platform;
    private static String admin;
    private static String alice;
    private static String bob;
    private static String orderService;

    @BeforeAll
    static void tokens() throws Exception {
        platform = Platform.shared();
        admin = platform.tokens.user("admin");
        alice = platform.tokens.user("alice");
        bob = platform.tokens.user("bob");
        orderService = platform.tokens.service("order-service", "order-service-secret");
    }

    @Test
    void onlyTheAdminRoleChangesTheCatalog() {
        String product = "{\"sku\":\"" + sku() + "\",\"name\":\"Access test\",\"unitPrice\":5.00,\"currency\":\"EUR\"}";

        assertThat(catalogAs(null).post("/api/v1/products", product).status()).isEqualTo(401);
        assertThat(catalogAs(alice).post("/api/v1/products", product).status()).isEqualTo(403);
        assertThat(catalogAs(orderService).post("/api/v1/products", product).status()).isEqualTo(403);
        assertThat(catalogAs(admin).post("/api/v1/products", product).status()).isEqualTo(201);
    }

    @Test
    void browsingTheCatalogIsPublic() {
        String sku = sku();
        catalogAs(admin).post("/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"Public\",\"unitPrice\":5.00,\"currency\":\"EUR\"}");

        assertThat(catalogAs(null).get("/api/v1/products/" + sku).status()).isEqualTo(200);
        assertThat(catalogAs(orderService).get("/api/v1/products/" + sku).status()).isEqualTo(200);
    }

    @Test
    void shipmentsAreForTheBackOfficeOnly() {
        String path = "/api/v1/shipments?orderId=" + UUID.randomUUID();

        assertThat(shippingAs(admin).get(path).status()).isEqualTo(404);
        assertThat(shippingAs(alice).get(path).status()).isEqualTo(403);
        assertThat(shippingAs(null).get(path).status()).isEqualTo(401);
    }

    @Test
    void theOrderServiceTokenIsOnlyValidWhereItsAudienceSays() {
        // The realm puts catalog-service and customer-service in its "aud", not shipping-service.
        assertThat(shippingAs(orderService).get("/api/v1/shipments?orderId=" + UUID.randomUUID()).status())
                .isEqualTo(401);
    }

    @Test
    void customersSeeOnlyTheirOwnOrders() {
        String sku = sku();
        catalogAs(admin).post("/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"Owned\",\"unitPrice\":5.00,\"currency\":\"EUR\"}");
        String aliceId = Shop.registered(new Api(platform.gateway.baseUrl(), alice), "Alice Archer");
        // order-service checked alice with customer-service using its own client-credentials token.
        Api.Response placed = ordersAs(alice).post("/api/v1/orders", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":1}]}");
        assertThat(placed.status()).isEqualTo(201);
        assertThat(placed.text("customerId")).isEqualTo(aliceId).isEqualTo("a11ce000-0000-4000-8000-000000000001");
        String order = "/api/v1/orders/" + placed.text("id");

        assertThat(ordersAs(alice).get(order).status()).isEqualTo(200);
        assertThat(ordersAs(bob).get(order).status()).isEqualTo(404);
        assertThat(ordersAs(bob).post(order + "/cancel", "{\"reason\":\"not mine\"}").status()).isEqualTo(404);
        assertThat(ordersAs(admin).get(order).status()).isEqualTo(200);
        assertThat(ordersAs(null).get(order).status()).isEqualTo(401);
    }

    @Test
    void customerProfilesAreReadableByServicesNotByOtherCustomers() {
        String aliceId = Shop.registered(new Api(platform.gateway.baseUrl(), alice), "Alice Archer");

        assertThat(new Api(platform.gateway.baseUrl(), orderService).get("/api/v1/customers/" + aliceId).status())
                .isEqualTo(200);
        assertThat(new Api(platform.gateway.baseUrl(), bob).get("/api/v1/customers/" + aliceId).status()).isEqualTo(403);
    }

    private static Api ordersAs(String token) {
        return new Api(platform.gateway.baseUrl(), token);
    }

    private static Api catalogAs(String token) {
        return new Api(platform.gateway.baseUrl(), token);
    }

    private static Api shippingAs(String token) {
        return new Api(platform.gateway.baseUrl(), token);
    }

    private static String sku() {
        return "ACL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
