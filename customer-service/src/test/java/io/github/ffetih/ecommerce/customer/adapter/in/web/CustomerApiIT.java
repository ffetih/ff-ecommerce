package io.github.ffetih.ecommerce.customer.adapter.in.web;

import io.github.ffetih.ecommerce.customer.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CustomerApiIT {

    @Value("${local.server.port}")
    int port;

    record Response(int status, String body) {
    }

    @Test
    void registersACustomerWithANormalizedEmail() {
        String email = uniqueEmail();

        Response created = register(email.toUpperCase());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).contains("\"email\":\"" + email + "\"").contains("\"active\":true");
    }

    @Test
    void rejectsTheSameEmailInDifferentCase() {
        String email = uniqueEmail();
        register(email);

        assertThat(register(email.toUpperCase()).status()).isEqualTo(409);
    }

    @Test
    void concurrentRegistrationsWithOneEmailCreateExactlyOneAccount() throws Exception {
        String email = uniqueEmail();
        var statuses = new ConcurrentLinkedQueue<Integer>();
        var startGate = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    startGate.await();
                    statuses.add(register(email).status());
                    return null;
                });
            }
            startGate.countDown();
        }

        // The existence check cannot see uncommitted rows; the unique constraint settles the race.
        assertThat(statuses).hasSize(10);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(9);
    }

    @Test
    void deactivatedCustomersCannotChangeTheirAddress() {
        String id = idOf(register(uniqueEmail()));

        Response deactivated = call(HttpMethod.POST, "/api/v1/customers/" + id + "/deactivate", null);
        Response change = call(HttpMethod.PUT, "/api/v1/customers/" + id + "/shipping-address",
                "{\"shippingAddress\":\"New address 1\"}");

        assertThat(deactivated.body()).contains("\"active\":false");
        assertThat(change.status()).isEqualTo(409);
    }

    @Test
    void reportsInvalidInputAndUnknownCustomers() {
        assertThat(call(HttpMethod.POST, "/api/v1/customers",
                "{\"email\":\"not-an-email\",\"fullName\":\"X\",\"shippingAddress\":\"Y\"}").status()).isEqualTo(400);
        assertThat(call(HttpMethod.GET, "/api/v1/customers/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    private Response register(String email) {
        return call(HttpMethod.POST, "/api/v1/customers",
                "{\"email\":\"" + email + "\",\"fullName\":\"Ada Lovelace\",\"shippingAddress\":\"12 Analytical St, London\"}");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String idOf(Response response) {
        int start = response.body().indexOf("\"id\":\"") + 6;
        return response.body().substring(start, start + 36);
    }

    private Response call(HttpMethod method, String path, String json) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        return new Response(response.getStatusCode().value(), response.getBody());
    }
}
