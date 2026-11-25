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

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP contract: status codes and RFC 9457 problem bodies, exercised over a real socket. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CatalogApiIT {

    @Value("${local.server.port}")
    int port;

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

    private Response call(HttpMethod method, String path, String json) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path);
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
