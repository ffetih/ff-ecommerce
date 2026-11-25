package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.events.order.OrderPlaced;
import io.github.ffetih.ecommerce.messaging.EventEnvelope;
import io.github.ffetih.ecommerce.order.support.StubServer;
import io.github.ffetih.ecommerce.order.support.StubServer.Response;
import io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order API over a real socket, with PostgreSQL and Kafka in containers and customer-service and
 * catalog-service replaced by stub servers we can make slow, broken or unaware of a customer. Callers
 * send signed tokens; the customer is always the token's subject.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OrderApiIT {

    static final StubServer CUSTOMER_SERVICE = StubServer.start();
    static final StubServer CATALOG_SERVICE = StubServer.start();

    @DynamicPropertySource
    static void downstreamServices(DynamicPropertyRegistry registry) {
        registry.add("ecommerce.clients.customer.base-url", CUSTOMER_SERVICE::baseUrl);
        registry.add("ecommerce.clients.catalog.base-url", CATALOG_SERVICE::baseUrl);
        registry.add("ecommerce.clients.catalog.read-timeout", () -> "1s");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    record Product(String sku, String price, boolean active) {
    }

    record ApiResponse(int status, HttpHeaders headers, String body) {

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    final Map<UUID, String> customers = new ConcurrentHashMap<>();
    final Map<String, Product> products = new ConcurrentHashMap<>();

    @BeforeEach
    void defaultDownstreamBehaviour() {
        customers.clear();
        products.clear();
        products.put("KB-01", new Product("KB-01", "89.90", true));
        products.put("MOUSE-01", new Product("MOUSE-01", "24.50", true));
        products.put("OLD-01", new Product("OLD-01", "5.00", false));
        CUSTOMER_SERVICE.answer(request -> {
            UUID id = UUID.fromString(request.path().substring(request.path().lastIndexOf('/') + 1));
            return Optional.ofNullable(customers.get(id)).map(Response::json).orElse(Response.status(404));
        });
        CATALOG_SERVICE.answer(this::productsFor);
    }

    @Test
    void placesAnOrderAtCatalogPricesAndPublishesOrderPlaced() {
        UUID customer = activeCustomer();

        // The client tries to send its own price; the field is not part of the API and is ignored.
        ApiResponse response = placeOrder(customer, UUID.randomUUID(),
                "[{\"sku\":\"KB-01\",\"quantity\":2,\"unitPrice\":0.01},{\"sku\":\"mouse-01\",\"quantity\":1}]");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.header("Location")).startsWith("/api/v1/orders/");
        JsonNode order = json.readTree(response.body());
        assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo("204.30");
        assertThat(order.get("status").asString()).isEqualTo("PLACED");
        assertThat(order.get("shippingAddress").asString()).isEqualTo("Bağdat Cd. 1, İstanbul");

        OrderPlaced event = orderPlacedEvent(order.get("id").asString());
        assertThat(event.totalAmount()).isEqualByComparingTo("204.30");
        assertThat(event.lines()).extracting(OrderPlaced.Line::sku).containsExactly("KB-01", "MOUSE-01");
    }

    @Test
    void concurrentRetriesWithOneKeyCreateExactlyOneOrder() {
        UUID customer = activeCustomer();
        UUID key = UUID.randomUUID();
        var responses = new ConcurrentLinkedQueue<ApiResponse>();
        var startGate = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    startGate.await();
                    responses.add(placeOrder(customer, key, "[{\"sku\":\"KB-01\",\"quantity\":1}]"));
                    return null;
                });
            }
            startGate.countDown();
        }

        assertThat(responses).extracting(ApiResponse::status).containsOnly(201, 200);
        assertThat(responses.stream().filter(r -> r.status() == 201)).hasSize(1);
        Set<String> orderIds = responses.stream()
                .map(r -> json.readTree(r.body()).get("id").asString())
                .collect(Collectors.toSet());
        assertThat(orderIds).hasSize(1);
        assertThat(ordersOf(customer)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentOrderIsAConflict() {
        UUID customer = activeCustomer();
        UUID key = UUID.randomUUID();
        placeOrder(customer, key, "[{\"sku\":\"KB-01\",\"quantity\":1}]");

        assertThat(placeOrder(customer, key, "[{\"sku\":\"KB-01\",\"quantity\":5}]").status()).isEqualTo(409);
    }

    @Test
    void rejectsUnknownCustomersAndUnknownOrRetiredProducts() {
        UUID customer = activeCustomer();

        assertThat(placeOrder(UUID.randomUUID(), UUID.randomUUID(), "[{\"sku\":\"KB-01\",\"quantity\":1}]").status())
                .isEqualTo(422);
        assertThat(placeOrder(customer, UUID.randomUUID(), "[{\"sku\":\"NOPE-01\",\"quantity\":1}]").status())
                .isEqualTo(422);
        assertThat(placeOrder(customer, UUID.randomUUID(), "[{\"sku\":\"OLD-01\",\"quantity\":1}]").status())
                .isEqualTo(422);
        assertThat(ordersOf(customer)).isZero();
    }

    @Test
    void aSlowCatalogTimesOutAsServiceUnavailableAndStoresNothing() {
        UUID customer = activeCustomer();
        CATALOG_SERVICE.answer(request -> productsFor(request).after(Duration.ofSeconds(3)));

        Instant start = Instant.now();
        ApiResponse response = placeOrder(customer, UUID.randomUUID(), "[{\"sku\":\"KB-01\",\"quantity\":1}]");

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.header("Retry-After")).isEqualTo("5");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(2_500));
        assertThat(ordersOf(customer)).isZero();
    }

    @Test
    void aFailingCatalogIsServiceUnavailableAndTheSameKeyWorksOnceItRecovers() {
        UUID customer = activeCustomer();
        UUID key = UUID.randomUUID();
        CATALOG_SERVICE.answer(request -> Response.status(500));

        assertThat(placeOrder(customer, key, "[{\"sku\":\"KB-01\",\"quantity\":1}]").status()).isEqualTo(503);

        CATALOG_SERVICE.answer(this::productsFor);
        assertThat(placeOrder(customer, key, "[{\"sku\":\"KB-01\",\"quantity\":1}]").status()).isEqualTo(201);
    }

    @Test
    void requiresAnIdempotencyKey() {
        UUID customer = activeCustomer();

        ApiResponse response = call(customerToken(customer), HttpMethod.POST, "/api/v1/orders", Map.of(),
                "{\"lines\":[{\"sku\":\"KB-01\",\"quantity\":1}]}");

        assertThat(response.status()).isEqualTo(400);
    }

    // --- who may do what -----------------------------------------------------------------------

    @Test
    void theOrderBelongsToTheCallerWhateverTheBodySays() {
        UUID alice = activeCustomer();
        UUID bob = activeCustomer();

        // An old client, or an attacker, still sends a customerId: it is not part of the API.
        ApiResponse response = call(customerToken(alice), HttpMethod.POST, "/api/v1/orders",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"customerId\":\"" + bob + "\",\"lines\":[{\"sku\":\"KB-01\",\"quantity\":1}]}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(json.readTree(response.body()).get("customerId").asString()).isEqualTo(alice.toString());
        assertThat(ordersOf(bob)).isZero();
    }

    @Test
    void customersSeeAndCancelOnlyTheirOwnOrders() {
        UUID alice = activeCustomer();
        UUID bob = activeCustomer();
        String order = idOf(placeOrder(alice, UUID.randomUUID(), "[{\"sku\":\"KB-01\",\"quantity\":1}]"));
        String cancel = "{\"reason\":\"not mine anyway\"}";

        // For bob, alice's order does not exist: 404, exactly like an unknown id.
        assertThat(call(customerToken(bob), HttpMethod.GET, "/api/v1/orders/" + order, Map.of(), null).status()).isEqualTo(404);
        assertThat(call(customerToken(bob), HttpMethod.POST, "/api/v1/orders/" + order + "/cancel", Map.of(), cancel).status())
                .isEqualTo(404);
        assertThat(call(customerToken(bob), HttpMethod.GET, "/api/v1/orders", Map.of(), null).body()).doesNotContain(order);
        assertThat(call(customerToken(bob), HttpMethod.GET, "/api/v1/orders?customerId=" + alice, Map.of(), null).status())
                .isEqualTo(403);

        ApiResponse own = call(customerToken(alice), HttpMethod.GET, "/api/v1/orders/" + order, Map.of(), null);
        assertThat(own.status()).isEqualTo(200);
        assertThat(json.readTree(own.body()).get("status").asString()).isEqualTo("PLACED");
        assertThat(call(customerToken(alice), HttpMethod.GET, "/api/v1/orders", Map.of(), null).body()).contains(order);

        // The back office sees every order.
        assertThat(call(adminToken(), HttpMethod.GET, "/api/v1/orders/" + order, Map.of(), null).status()).isEqualTo(200);
        assertThat(call(adminToken(), HttpMethod.GET, "/api/v1/orders?customerId=" + alice, Map.of(), null).body())
                .contains(order);
    }

    @Test
    void onlyCustomersPlaceOrders() {
        String body = "{\"lines\":[{\"sku\":\"KB-01\",\"quantity\":1}]}";
        Map<String, String> key = Map.of("Idempotency-Key", UUID.randomUUID().toString());

        assertThat(call(null, HttpMethod.POST, "/api/v1/orders", key, body).status()).isEqualTo(401);
        assertThat(call(adminToken(), HttpMethod.POST, "/api/v1/orders", key, body).status()).isEqualTo(403);
    }

    @Test
    void downstreamCallsCarryOrderServicesOwnTokenNotTheCallers() {
        UUID alice = activeCustomer();
        String aliceToken = customerToken(alice);
        placeOrder(alice, UUID.randomUUID(), "[{\"sku\":\"KB-01\",\"quantity\":1}]");
        int tokensIssued = tokens().issuedTo("order-service").size();

        call(aliceToken, HttpMethod.POST, "/api/v1/orders", Map.of("Idempotency-Key", UUID.randomUUID().toString()),
                "{\"lines\":[{\"sku\":\"MOUSE-01\",\"quantity\":1}]}");

        String toCustomerService = last(CUSTOMER_SERVICE).authorization();
        String toCatalogService = last(CATALOG_SERVICE).authorization();
        assertThat(toCustomerService).startsWith("Bearer ").isNotEqualTo("Bearer " + aliceToken);
        assertThat(tokens().issuedTo("order-service")).contains(toCustomerService.substring("Bearer ".length()));
        assertThat(toCatalogService).isEqualTo(toCustomerService);
        // The token is kept until shortly before it expires, not fetched again for every order.
        assertThat(tokens().issuedTo("order-service")).hasSize(tokensIssued);
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID activeCustomer() {
        UUID id = UUID.randomUUID();
        customers.put(id, "{\"id\":\"" + id + "\",\"email\":\"x@example.com\",\"fullName\":\"X\","
                + "\"shippingAddress\":\"Bağdat Cd. 1, İstanbul\",\"active\":true}");
        return id;
    }

    private Response productsFor(StubServer.Request request) {
        List<String> requested = request.query() == null ? List.of() : Arrays.stream(request.query().split("&"))
                .filter(param -> param.startsWith("sku="))
                .map(param -> param.substring(4))
                .toList();
        String body = requested.stream()
                .map(products::get)
                .filter(product -> product != null)
                .map(p -> "{\"sku\":\"" + p.sku() + "\",\"name\":\"" + p.sku() + "\",\"unitPrice\":" + p.price()
                        + ",\"currency\":\"EUR\",\"active\":" + p.active() + "}")
                .collect(Collectors.joining(",", "[", "]"));
        return Response.json(body);
    }

    private ApiResponse placeOrder(UUID customer, UUID key, String linesJson) {
        return call(customerToken(customer), HttpMethod.POST, "/api/v1/orders", Map.of("Idempotency-Key", key.toString()),
                "{\"lines\":" + linesJson + "}");
    }

    private static String customerToken(UUID customer) {
        return tokens().token(customer.toString()).audiences("order-service").roles("customer").sign();
    }

    private static String adminToken() {
        return tokens().token("ad000000-0000-4000-8000-000000000003").audiences("order-service").roles("admin").sign();
    }

    private String idOf(ApiResponse response) {
        assertThat(response.status()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asString();
    }

    private static StubServer.Request last(StubServer stub) {
        List<StubServer.Request> received = stub.received();
        return received.get(received.size() - 1);
    }

    private int ordersOf(UUID customer) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = ?", Integer.class, customer);
    }

    private ApiResponse call(String token, HttpMethod method, String path, Map<String, String> headers, String jsonBody) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path)
                .headers(httpHeaders -> {
                    headers.forEach(httpHeaders::add);
                    if (token != null) {
                        httpHeaders.setBearerAuth(token);
                    }
                });
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        return new ApiResponse(response.getStatusCode().value(), response.getHeaders(), response.getBody());
    }

    private OrderPlaced orderPlacedEvent(String orderId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "order-api-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<OrderPlaced> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(OrderPlaced.TOPIC));
            Instant deadline = Instant.now().plusSeconds(20);
            while (found.isEmpty() && Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(250))) {
                    if (orderId.equals(record.key())) {
                        EventEnvelope envelope = json.readValue(record.value(), EventEnvelope.class);
                        found.add(json.treeToValue(envelope.data(), OrderPlaced.class));
                    }
                }
            }
        }
        assertThat(found).as("OrderPlaced event for " + orderId).hasSize(1);
        return found.getFirst();
    }
}
