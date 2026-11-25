package io.github.ffetih.ecommerce.catalog.adapter.in.web;

import io.github.ffetih.ecommerce.catalog.support.TestData;
import io.github.ffetih.ecommerce.catalog.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static io.github.ffetih.ecommerce.catalog.support.TestcontainersConfiguration.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP contract: status codes, RFC 9457 problem bodies and who may call what, exercised over a real
 * socket with signed tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CatalogApiIT {

    @Value("${local.server.port}")
    int port;

    static final String ADMIN_ID = "ad000000-0000-4000-8000-000000000003";
    static final String ALICE_ID = "a11ce000-0000-4000-8000-000000000001";

    record Response(int status, String contentType, String body) {
    }

    @Test
    void createsAProductWithANormalizedSkuAndExactPrice() {
        String sku = TestData.uniqueSku("kb").value().toLowerCase();

        Response created = call(HttpMethod.POST, "/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"Keyboard\",\"unitPrice\":89.9,\"currency\":\"eur\"}");

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body())
                .contains("\"sku\":\"" + sku.toUpperCase() + "\"")
                .contains("\"unitPrice\":89.90")
                .contains("\"currency\":\"EUR\"");
    }

    @Test
    void reportsInsufficientStockAsAConflictWithTheAvailableQuantity() {
        String sku = createProductWithStock(7);

        Response response = call(HttpMethod.PUT, "/api/v1/reservations/" + UUID.randomUUID(),
                "{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":50}]}");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"requested\":50").contains("\"available\":7");
    }

    @Test
    void rejectsInvalidInputWithBadRequest() {
        assertThat(call(HttpMethod.GET, "/api/v1/stock/KB_01", null).status()).isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/api/v1/products",
                "{\"sku\":\"KB-X1\",\"name\":\"Keyboard\",\"unitPrice\":1.999,\"currency\":\"EUR\"}").status())
                .isEqualTo(400);
        assertThat(call(HttpMethod.POST, "/api/v1/stock/KB-X1/receipts",
                "{\"referenceId\":\"" + UUID.randomUUID() + "\",\"quantity\":0}").status())
                .isEqualTo(400);
    }

    @Test
    void returnsNotFoundForUnknownProductsAndReservations() {
        assertThat(call(HttpMethod.GET, "/api/v1/products/NO-SUCH-SKU", null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/reservations/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    @Test
    void rejectsADuplicateProduct() {
        String sku = createProductWithStock(0);

        Response duplicate = call(HttpMethod.POST, "/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"Again\",\"unitPrice\":1.00,\"currency\":\"EUR\"}");

        assertThat(duplicate.status()).isEqualTo(409);
    }

    private String createProductWithStock(int quantity) {
        String sku = TestData.uniqueSku("API").value();
        call(HttpMethod.POST, "/api/v1/products",
                "{\"sku\":\"" + sku + "\",\"name\":\"Test\",\"unitPrice\":10.00,\"currency\":\"EUR\"}");
        if (quantity > 0) {
            call(HttpMethod.POST, "/api/v1/stock/" + sku + "/receipts",
                    "{\"referenceId\":\"" + UUID.randomUUID() + "\",\"quantity\":" + quantity + "}");
        }
        return sku;
    }

    @Test
    void concurrentCreationsOfOneSkuCreateExactlyOneProduct() throws Exception {
        String sku = TestData.uniqueSku("RACE").value();
        var statuses = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        var startGate = new java.util.concurrent.CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                int attempt = i;
                executor.submit(() -> {
                    startGate.await();
                    statuses.add(call(HttpMethod.POST, "/api/v1/products", "{\"sku\":\"" + sku
                            + "\",\"name\":\"Attempt " + attempt + "\",\"unitPrice\":10.00,\"currency\":\"EUR\"}").status());
                    return null;
                });
            }
            startGate.countDown();
        }

        assertThat(statuses).hasSize(10);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(9);
    }

    @Test
    void aDeactivatedProductStaysVisibleAsInactive() {
        String sku = createProductWithStock(0);

        Response deactivated = call(HttpMethod.POST, "/api/v1/products/" + sku + "/deactivate", null);
        Response again = call(HttpMethod.POST, "/api/v1/products/" + sku + "/deactivate", null);

        assertThat(deactivated.status()).isEqualTo(200);
        assertThat(deactivated.body()).contains("\"active\":false");
        assertThat(again.status()).isEqualTo(200);
        assertThat(callAs(null, HttpMethod.GET, "/api/v1/products/" + sku, null).body()).contains("\"active\":false");
        assertThat(call(HttpMethod.POST, "/api/v1/products/NO-SUCH-SKU/deactivate", null).status()).isEqualTo(404);
    }

    @Test
    void anyoneMayBrowseTheCatalog() {
        String sku = createProductWithStock(0);

        assertThat(callAs(null, HttpMethod.GET, "/api/v1/products/" + sku, null).status()).isEqualTo(200);
        assertThat(callAs(null, HttpMethod.GET, "/api/v1/products?sku=" + sku, null).body()).contains(sku);
    }

    @Test
    void onlyAdminsChangeTheCatalogAndTheStock() {
        String sku = createProductWithStock(3);
        String product = "{\"sku\":\"" + TestData.uniqueSku("SEC").value() + "\",\"name\":\"X\",\"unitPrice\":1.00,\"currency\":\"EUR\"}";
        String price = "{\"unitPrice\":2.00,\"currency\":\"EUR\"}";
        String receipt = "{\"referenceId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}";
        String reservation = "{\"lines\":[{\"sku\":\"" + sku + "\",\"quantity\":1}]}";

        // Anonymous: 401. A customer, or order-service's machine token: authenticated, but not admin: 403.
        for (String caller : new String[]{null, customer(), orderService()}) {
            int expected = caller == null ? 401 : 403;
            assertThat(callAs(caller, HttpMethod.POST, "/api/v1/products", product).status()).isEqualTo(expected);
            assertThat(callAs(caller, HttpMethod.PUT, "/api/v1/products/" + sku + "/price", price).status()).isEqualTo(expected);
            assertThat(callAs(caller, HttpMethod.POST, "/api/v1/products/" + sku + "/deactivate", null).status())
                    .isEqualTo(expected);
            assertThat(callAs(caller, HttpMethod.GET, "/api/v1/stock/" + sku, null).status()).isEqualTo(expected);
            assertThat(callAs(caller, HttpMethod.POST, "/api/v1/stock/" + sku + "/receipts", receipt).status()).isEqualTo(expected);
            assertThat(callAs(caller, HttpMethod.PUT, "/api/v1/reservations/" + UUID.randomUUID(), reservation).status())
                    .isEqualTo(expected);
        }
        assertThat(callAs(admin(), HttpMethod.GET, "/api/v1/stock/" + sku, null).body()).contains("\"onHand\":3");
        assertThat(callAs(null, HttpMethod.GET, "/api/v1/products/" + sku, null).body()).contains("\"active\":true");
    }

    @Test
    void aTokenForAnotherServiceIsRejectedEvenOnPublicEndpoints() {
        String sku = createProductWithStock(0);
        String shippingToken = tokens().token(ADMIN_ID).audiences("shipping-service").roles("admin").sign();

        // A token that is present but invalid is an error, not "anonymous".
        assertThat(callAs(shippingToken, HttpMethod.GET, "/api/v1/products/" + sku, null).status()).isEqualTo(401);
        assertThat(callAs(shippingToken, HttpMethod.GET, "/api/v1/stock/" + sku, null).status()).isEqualTo(401);
    }

    private static String admin() {
        return tokens().token(ADMIN_ID).audiences("catalog-service").roles("admin").sign();
    }

    private static String customer() {
        return tokens().token(ALICE_ID).audiences("catalog-service").roles("customer").sign();
    }

    private static String orderService() {
        return tokens().serviceToken("order-service").audiences("catalog-service", "customer-service").sign();
    }

    /** Most tests are about the API contract, not access: they call as an admin. */
    private Response call(HttpMethod method, String path, String json) {
        return callAs(admin(), method, path, json);
    }

    private Response callAs(String token, HttpMethod method, String path, String json) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        // Treat every status as a normal response so the test can assert on 4xx bodies.
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        return new Response(
                response.getStatusCode().value(),
                String.valueOf(response.getHeaders().getContentType()),
                response.getBody());
    }
}
