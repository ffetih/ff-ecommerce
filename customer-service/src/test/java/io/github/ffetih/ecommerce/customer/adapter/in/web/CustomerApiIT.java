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

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static io.github.ffetih.ecommerce.customer.support.TestcontainersConfiguration.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The customer API over a real socket with signed tokens. Each test invents its own people: a
 * person is a token subject plus an email, as Keycloak would issue them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CustomerApiIT {

    static final String PROFILE = "{\"fullName\":\"Ada Lovelace\",\"shippingAddress\":\"12 Analytical St, London\"}";

    @Value("${local.server.port}")
    int port;

    record Response(int status, String body) {
    }

    /** A person who can log in: their customer id is the token subject. */
    record Person(UUID id, String email) {

        static Person create() {
            UUID id = UUID.randomUUID();
            return new Person(id, "user-" + id + "@example.com");
        }

        String token() {
            return tokens().token(id.toString()).audiences("customer-service").roles("customer").claim("email", email).sign();
        }
    }

    @Test
    void registersTheCallerWithIdAndEmailFromTheToken() {
        Person ada = Person.create();
        String shouting = tokens().token(ada.id().toString()).audiences("customer-service").roles("customer")
                .claim("email", ada.email().toUpperCase(Locale.ROOT)).sign();

        Response created = call(shouting, HttpMethod.POST, "/api/v1/customers/me", PROFILE);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body())
                .contains("\"id\":\"" + ada.id() + "\"")
                .contains("\"email\":\"" + ada.email() + "\"")
                .contains("\"active\":true");
        assertThat(call(ada.token(), HttpMethod.GET, "/api/v1/customers/me", null).body()).contains(ada.id().toString());
    }

    @Test
    void registeringTwiceIsAConflict() {
        Person ada = Person.create();
        call(ada.token(), HttpMethod.POST, "/api/v1/customers/me", PROFILE);

        assertThat(call(ada.token(), HttpMethod.POST, "/api/v1/customers/me", PROFILE).status()).isEqualTo(409);
    }

    @Test
    void twoPeopleCannotShareAnEmail() {
        Person ada = Person.create();
        call(ada.token(), HttpMethod.POST, "/api/v1/customers/me", PROFILE);
        String impostor = tokens().token(UUID.randomUUID().toString()).audiences("customer-service").roles("customer")
                .claim("email", ada.email()).sign();

        assertThat(call(impostor, HttpMethod.POST, "/api/v1/customers/me", PROFILE).status()).isEqualTo(409);
    }

    @Test
    void concurrentRegistrationsOfOnePersonCreateExactlyOneAccount() throws Exception {
        Person ada = Person.create();
        var statuses = new ConcurrentLinkedQueue<Integer>();
        var startGate = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {
                    startGate.await();
                    statuses.add(call(ada.token(), HttpMethod.POST, "/api/v1/customers/me", PROFILE).status());
                    return null;
                });
            }
            startGate.countDown();
        }

        // The existence checks cannot see uncommitted rows; the primary key settles the race.
        assertThat(statuses).hasSize(10);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(9);
    }

    @Test
    void customersReachOnlyTheirOwnProfile() {
        Person ada = registered();
        Person bob = registered();

        assertThat(call(ada.token(), HttpMethod.GET, "/api/v1/customers/me", null).body()).contains(ada.id().toString());
        assertThat(call(ada.token(), HttpMethod.GET, "/api/v1/customers/" + bob.id(), null).status()).isEqualTo(403);
        assertThat(call(ada.token(), HttpMethod.POST, "/api/v1/customers/" + bob.id() + "/deactivate", null).status())
                .isEqualTo(403);
        assertThat(call(null, HttpMethod.GET, "/api/v1/customers/me", null).status()).isEqualTo(401);
    }

    @Test
    void servicesAndTheBackOfficeLookUpCustomersById() {
        Person ada = registered();
        String orderService = tokens().serviceToken("order-service").audiences("customer-service").sign();

        assertThat(call(orderService, HttpMethod.GET, "/api/v1/customers/" + ada.id(), null).body())
                .contains("\"active\":true");
        assertThat(call(admin(), HttpMethod.GET, "/api/v1/customers/" + ada.id(), null).status()).isEqualTo(200);
        assertThat(call(orderService, HttpMethod.GET, "/api/v1/customers/" + UUID.randomUUID(), null).status())
                .isEqualTo(404);
        // A service may read customers, not manage them.
        assertThat(call(orderService, HttpMethod.POST, "/api/v1/customers/" + ada.id() + "/deactivate", null).status())
                .isEqualTo(403);
    }

    @Test
    void deactivatedCustomersCannotChangeTheirAddress() {
        Person ada = registered();

        Response deactivated = call(admin(), HttpMethod.POST, "/api/v1/customers/" + ada.id() + "/deactivate", null);
        Response change = call(ada.token(), HttpMethod.PUT, "/api/v1/customers/me/shipping-address",
                "{\"shippingAddress\":\"New address 1\"}");

        assertThat(deactivated.body()).contains("\"active\":false");
        assertThat(change.status()).isEqualTo(409);
    }

    @Test
    void reportsInvalidInputAndUnknownCustomers() {
        Person ada = Person.create();
        String withoutEmail = tokens().token(ada.id().toString()).audiences("customer-service").roles("customer").sign();

        assertThat(call(ada.token(), HttpMethod.POST, "/api/v1/customers/me", "{\"fullName\":\"X\",\"shippingAddress\":\" \"}")
                .status()).isEqualTo(400);
        assertThat(call(withoutEmail, HttpMethod.POST, "/api/v1/customers/me", PROFILE).status()).isEqualTo(400);
        assertThat(call(ada.token(), HttpMethod.GET, "/api/v1/customers/me", null).status()).isEqualTo(404);
    }

    private Person registered() {
        Person person = Person.create();
        assertThat(call(person.token(), HttpMethod.POST, "/api/v1/customers/me", PROFILE).status()).isEqualTo(201);
        return person;
    }

    private static String admin() {
        return tokens().token("ad000000-0000-4000-8000-000000000003").audiences("customer-service").roles("admin").sign();
    }

    private Response call(String token, HttpMethod method, String path, String json) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        if (json != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        return new Response(response.getStatusCode().value(), response.getBody());
    }
}
