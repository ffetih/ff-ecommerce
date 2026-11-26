package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.order.application.port.OrderRepository;
import io.github.ffetih.ecommerce.order.application.port.Transactions;
import io.github.ffetih.ecommerce.order.domain.Money;
import io.github.ffetih.ecommerce.order.domain.Order;
import io.github.ffetih.ecommerce.order.domain.OrderLine;
import io.github.ffetih.ecommerce.order.domain.Sku;
import io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

import static io.github.ffetih.ecommerce.order.support.TestcontainersConfiguration.tokens;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Returns over a real socket against PostgreSQL (ADR-0010). The orders are stored as delivered
 * directly; how they got there is the saga's business, tested elsewhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReturnApiIT {

    private static final String KEYBOARD_TWICE = "{\"lines\":[{\"sku\":\"KB-01\",\"quantity\":2}],\"reason\":\"too loud\"}";
    private static final String ONE_KEYBOARD = "{\"lines\":[{\"sku\":\"KB-01\",\"quantity\":1}],\"reason\":\"too loud\"}";

    @Value("${local.server.port}")
    int port;

    @Autowired
    OrderRepository orders;

    @Autowired
    Transactions transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    record ApiResponse(int status, String body) {
    }

    private final UUID customer = UUID.randomUUID();

    @Test
    void aReturnIsRefundedAtTheOrdersFrozenPricesAndCountedPerLine() {
        UUID order = deliveredOrder(Instant.now());

        ApiResponse created = requestReturn(order, UUID.randomUUID(), ONE_KEYBOARD);

        assertThat(created.status()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        assertThat(body.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(body.get("refundAmount").decimalValue()).isEqualByComparingTo("89.90");
        JsonNode listed = json.readTree(call(customerToken(), HttpMethod.GET, "/api/v1/orders/" + order + "/returns", Map.of(), null).body());
        assertThat(listed.get("returns").size()).isEqualTo(1);
        assertThat(listed.get("returnedUnits").get("KB-01").asInt()).isEqualTo(1);
    }

    @Test
    void repeatingARequestWithTheSameKeyReturnsTheSameReturn() {
        UUID order = deliveredOrder(Instant.now());
        UUID key = UUID.randomUUID();

        String first = id(requestReturn(order, key, ONE_KEYBOARD));
        ApiResponse again = requestReturn(order, key, ONE_KEYBOARD);

        assertThat(again.status()).isEqualTo(200);
        assertThat(json.readTree(again.body()).get("id").asString()).isEqualTo(first);
        assertThat(requestReturn(order, key, KEYBOARD_TWICE).status()).as("same key, other content").isEqualTo(409);
        assertThat(returned(order)).isEqualTo(1);
    }

    /**
     * Two keyboards were bought. Ten requests for one keyboard each arrive at the same moment, with
     * different keys (two tabs, two people): exactly two can succeed, whatever the timing.
     */
    @Test
    void concurrentRequestsNeverReturnMoreThanWasBought() throws Exception {
        UUID order = deliveredOrder(Instant.now());
        var statuses = new ConcurrentLinkedQueue<Integer>();
        var start = new CountDownLatch(1);

        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 10; i++) {
                clients.submit(() -> {
                    start.await();
                    statuses.add(requestReturn(order, UUID.randomUUID(), ONE_KEYBOARD).status());
                    return null;
                });
            }
            start.countDown();
        }

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(2);
        assertThat(statuses).filteredOn(status -> status == 422).hasSize(8);
        assertThat(returned(order)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM returns WHERE order_id = ?", Integer.class, order)).isEqualTo(2);
    }

    @Test
    void aRejectedReturnGivesItsUnitsBack() {
        UUID order = deliveredOrder(Instant.now());
        String first = id(requestReturn(order, UUID.randomUUID(), KEYBOARD_TWICE));
        assertThat(requestReturn(order, UUID.randomUUID(), ONE_KEYBOARD).status()).as("nothing left").isEqualTo(422);

        ApiResponse rejected = call(adminToken(), HttpMethod.POST, "/api/v1/returns/" + first + "/reject", Map.of(),
                "{\"reason\":\"never arrived\"}");

        assertThat(json.readTree(rejected.body()).get("status").asString()).isEqualTo("REJECTED");
        assertThat(returned(order)).isZero();
        assertThat(requestReturn(order, UUID.randomUUID(), KEYBOARD_TWICE).status()).isEqualTo(201);
    }

    @Test
    void theBackOfficeReceivesTheParcelOnceAndCustomersCannot() {
        UUID order = deliveredOrder(Instant.now());
        String returnId = id(requestReturn(order, UUID.randomUUID(), ONE_KEYBOARD));
        String receive = "/api/v1/returns/" + returnId + "/receive";

        assertThat(call(customerToken(), HttpMethod.POST, receive, Map.of(), null).status()).isEqualTo(403);
        ApiResponse received = call(adminToken(), HttpMethod.POST, receive, Map.of(), null);
        ApiResponse again = call(adminToken(), HttpMethod.POST, receive, Map.of(), null);

        assertThat(json.readTree(received.body()).get("status").asString()).isEqualTo("RECEIVED");
        assertThat(again.status()).isEqualTo(200);
        assertThat(call(adminToken(), HttpMethod.POST, "/api/v1/returns/" + returnId + "/reject", Map.of(),
                "{\"reason\":\"too late\"}").status()).as("received cannot be rejected").isEqualTo(409);
    }

    @Test
    void refusesOrdersThatAreNotDeliveredTooOldUnpaidOrSomeoneElses() {
        UUID shipped = stored(order -> order.stockReserved(Instant.now()).paymentAuthorized(Instant.now()).ship(Instant.now()));
        UUID old = deliveredOrder(Instant.now().minus(Duration.ofDays(15)));
        UUID overdue = stored(order -> order.stockReserved(Instant.now()).paymentAuthorized(Instant.now()).ship(Instant.now())
                .paymentCaptureFailed(Instant.now()).deliver(Instant.now()));
        UUID mine = deliveredOrder(Instant.now());

        assertThat(requestReturn(shipped, UUID.randomUUID(), ONE_KEYBOARD).status()).isEqualTo(409);
        assertThat(requestReturn(old, UUID.randomUUID(), ONE_KEYBOARD).status()).isEqualTo(422);
        assertThat(requestReturn(overdue, UUID.randomUUID(), ONE_KEYBOARD).status()).isEqualTo(422);
        String stranger = tokens().token(UUID.randomUUID().toString()).audiences("order-service").roles("customer").sign();
        assertThat(call(stranger, HttpMethod.POST, "/api/v1/orders/" + mine + "/returns",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()), ONE_KEYBOARD).status()).isEqualTo(404);
        assertThat(requestReturn(mine, UUID.randomUUID(), "{\"lines\":[{\"sku\":\"KB-01\",\"quantity\":1}]}").status())
                .as("no reason").isEqualTo(400);
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Two keyboards and a mouse, delivered (and paid) at {@code at}. */
    private UUID deliveredOrder(Instant at) {
        return stored(order -> order.stockReserved(at).paymentAuthorized(at).ship(at).paymentCaptured(at).deliver(at));
    }

    private UUID stored(UnaryOperator<Order> steps) {
        Order placed = Order.place(customer,
                List.of(new OrderLine(Sku.of("KB-01"), 2, Money.of("89.90", "EUR")),
                        new OrderLine(Sku.of("MOUSE-01"), 1, Money.of("24.50", "EUR"))),
                "Bağdat Cd. 1, İstanbul", "tok_ok", Instant.now());
        Order order = steps.apply(placed);
        transactions.inTransaction(() -> {
            orders.insert(order, UUID.randomUUID(), "return-it");
            return null;
        });
        return order.id();
    }

    private int returned(UUID order) {
        return jdbc.queryForList("SELECT returned FROM returned_units WHERE order_id = ? AND sku = 'KB-01'", Integer.class, order)
                .stream().findFirst().orElse(0);
    }

    private ApiResponse requestReturn(UUID order, UUID key, String body) {
        return call(customerToken(), HttpMethod.POST, "/api/v1/orders/" + order + "/returns",
                Map.of("Idempotency-Key", key.toString()), body);
    }

    private String id(ApiResponse response) {
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body()).get("id").asString();
    }

    private String customerToken() {
        return tokens().token(customer.toString()).audiences("order-service").roles("customer").sign();
    }

    private static String adminToken() {
        return tokens().token("ad000000-0000-4000-8000-000000000003").audiences("order-service").roles("admin").sign();
    }

    private ApiResponse call(String token, HttpMethod method, String path, Map<String, String> headers, String jsonBody) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path)
                .headers(httpHeaders -> {
                    headers.forEach(httpHeaders::add);
                    httpHeaders.setBearerAuth(token);
                });
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        return new ApiResponse(response.getStatusCode().value(), response.getBody());
    }
}
