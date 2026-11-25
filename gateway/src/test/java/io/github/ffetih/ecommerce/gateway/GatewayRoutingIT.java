package io.github.ffetih.ecommerce.gateway;

import io.github.ffetih.ecommerce.testsupport.StubServer;
import io.github.ffetih.ecommerce.testsupport.StubServer.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The gateway in front of four stub services: which path goes where, and what passes through unchanged. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIT {

    static final Map<String, StubServer> SERVICES = Map.of(
            "catalog", StubServer.start(),
            "customer", StubServer.start(),
            "order", StubServer.start(),
            "shipping", StubServer.start());

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        SERVICES.forEach((name, stub) -> registry.add(name.toUpperCase(Locale.ROOT) + "_SERVICE_URL", stub::baseUrl));
    }

    @Value("${local.server.port}")
    int port;

    record Answer(int status, String body) {
    }

    @BeforeEach
    void everyServiceSaysWhoItIs() {
        SERVICES.forEach((name, stub) -> stub.answer(request -> Response.json("{\"service\":\"" + name + "\"}")));
    }

    @ParameterizedTest
    @CsvSource({
            "GET,  /api/v1/products,                    catalog",
            "GET,  /api/v1/products/KB-01,              catalog",
            "POST, /api/v1/stock/KB-01/receipts,        catalog",
            "PUT,  /api/v1/reservations/b0b0,           catalog",
            "GET,  /api/v1/customers/me,                customer",
            "POST, /api/v1/orders,                      order",
            "POST, /api/v1/orders/42/cancel,            order",
            "GET,  /api/v1/shipments,                   shipping",
            "POST, /api/v1/shipments/7/dispatch,        shipping",
    })
    void routesEachApiPathToTheServiceThatOwnsIt(String method, String path, String service) {
        Answer answer = call(HttpMethod.valueOf(method), path, null, method.equals("GET") ? null : "{}");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("\"service\":\"" + service + "\"");
        StubServer.Request received = last(service);
        assertThat(received.method()).isEqualTo(method);
        assertThat(received.path()).isEqualTo(path);
    }

    @Test
    void passesTheBearerTokenAndTheQueryThroughUnchanged() {
        call(HttpMethod.GET, "/api/v1/products?sku=KB-01&sku=MOUSE-01", "Bearer abc.def.ghi", null);

        StubServer.Request received = last("catalog");
        assertThat(received.authorization()).isEqualTo("Bearer abc.def.ghi");
        assertThat(received.query()).isEqualTo("sku=KB-01&sku=MOUSE-01");
    }

    @Test
    void passesTheServicesDecisionBackUnchanged() {
        SERVICES.get("order").answer(request -> Response.status(403));

        assertThat(call(HttpMethod.GET, "/api/v1/orders/42", null, null).status()).isEqualTo(403);
    }

    /** The old gateway proxied documentation paths to any host; nothing outside the API is forwarded now. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/docs", "/docs/catalog/v3/api-docs", "/v3/api-docs", "/swagger-ui/index.html",
            "/api/v2/products", "/api/v1/productsX", "/api/v1/unknown", "/actuator/env", "/actuator/gateway/routes"})
    void forwardsNothingOutsideTheApi(String path) {
        int before = totalReceived();

        assertThat(call(HttpMethod.GET, path, null, null).status()).isEqualTo(404);
        assertThat(totalReceived()).isEqualTo(before);
    }

    @Test
    void reportsItsOwnHealth() {
        assertThat(call(HttpMethod.GET, "/actuator/health", null, null).body()).contains("\"status\":\"UP\"");
    }

    private static StubServer.Request last(String service) {
        List<StubServer.Request> received = SERVICES.get(service).received();
        assertThat(received).as("requests received by " + service).isNotEmpty();
        return received.get(received.size() - 1);
    }

    private static int totalReceived() {
        return SERVICES.values().stream().mapToInt(stub -> stub.received().size()).sum();
    }

    private Answer call(HttpMethod method, String path, String authorization, String json) {
        RestClient.RequestBodySpec request = RestClient.create().method(method)
                .uri(URI.create("http://localhost:" + port + path));
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        ResponseEntity<String> response = request.retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(String.class);
        return new Answer(response.getStatusCode().value(), response.getBody());
    }
}
