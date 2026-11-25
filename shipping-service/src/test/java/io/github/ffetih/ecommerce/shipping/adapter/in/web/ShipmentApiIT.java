package io.github.ffetih.ecommerce.shipping.adapter.in.web;

import io.github.ffetih.ecommerce.shipping.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static io.github.ffetih.ecommerce.shipping.support.TestcontainersConfiguration.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call the shipping API. Shipments themselves are created from order events (see
 * ShippingSagaIT); here unknown ids are enough: a 404 means the caller got past security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ShipmentApiIT {

    static final String ADMIN_ID = "ad000000-0000-4000-8000-000000000003";
    static final String ALICE_ID = "a11ce000-0000-4000-8000-000000000001";

    @Value("${local.server.port}")
    int port;

    @Test
    void adminsReachTheShipmentEndpoints() {
        String admin = tokens().token(ADMIN_ID).audiences("shipping-service").roles("admin").sign();

        assertThat(status(admin, HttpMethod.GET, "/api/v1/shipments/" + UUID.randomUUID())).isEqualTo(404);
        assertThat(status(admin, HttpMethod.GET, "/api/v1/shipments?orderId=" + UUID.randomUUID())).isEqualTo(404);
        assertThat(status(admin, HttpMethod.POST, "/api/v1/shipments/" + UUID.randomUUID() + "/dispatch")).isEqualTo(404);
        assertThat(status(admin, HttpMethod.POST, "/api/v1/shipments/" + UUID.randomUUID() + "/deliver")).isEqualTo(404);
    }

    @Test
    void customersAreForbidden() {
        String alice = tokens().token(ALICE_ID).audiences("shipping-service").roles("customer").sign();

        assertThat(status(alice, HttpMethod.GET, "/api/v1/shipments/" + UUID.randomUUID())).isEqualTo(403);
        assertThat(status(alice, HttpMethod.POST, "/api/v1/shipments/" + UUID.randomUUID() + "/dispatch")).isEqualTo(403);
    }

    @Test
    void anonymousCallersAreChallenged() {
        assertThat(status(null, HttpMethod.GET, "/api/v1/shipments/" + UUID.randomUUID())).isEqualTo(401);
        assertThat(status(null, HttpMethod.POST, "/api/v1/shipments/" + UUID.randomUUID() + "/deliver")).isEqualTo(401);
    }

    @Test
    void orderServiceTokensAreNotMeantForShipping() {
        // Signed by the right issuer, but "aud" names only catalog and customer: rejected as invalid.
        String orderService = tokens().serviceToken("order-service").audiences("catalog-service", "customer-service").sign();

        assertThat(status(orderService, HttpMethod.GET, "/api/v1/shipments/" + UUID.randomUUID())).isEqualTo(401);
    }

    private int status(String token, HttpMethod method, String path) {
        var request = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        ResponseEntity<String> response = request.retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(String.class);
        return response.getStatusCode().value();
    }
}
