package io.github.ffetih.ecommerce.gateway;

import io.github.ffetih.ecommerce.testsupport.StubServer;
import io.github.ffetih.ecommerce.testsupport.StubServer.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A service that gives no valid answer, or answers too late, is reported as such: 502 and 504, not 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.http.clients.read-timeout=1s")
class UnreachableServiceIT {

    static final StubServer SLOW_CATALOG = StubServer.start();

    /**
     * A broken shipping-service: accepts connections and hangs up without answering. A port where
     * nothing listens would not do: whether connecting to it is refused at once or silently times
     * out depends on the machine's network setup (WSL mirrored networking, firewalls), not on us.
     */
    static final ServerSocket BROKEN_SHIPPING = hangsUpOnEveryone();

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("CATALOG_SERVICE_URL", SLOW_CATALOG::baseUrl);
        registry.add("SHIPPING_SERVICE_URL", () -> "http://localhost:" + BROKEN_SHIPPING.getLocalPort());
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void aServiceThatGivesNoValidAnswerIsABadGateway() {
        ResponseEntity<String> response = get("/api/v1/shipments/1");

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(String.valueOf(response.getHeaders().getContentType())).startsWith("application/problem+json");
        assertThat(response.getBody()).contains("\"status\":502").doesNotContain("localhost");
    }

    @Test
    void aServiceThatDoesNotAnswerInTimeIsAGatewayTimeout() {
        SLOW_CATALOG.answer(request -> Response.json("[]").after(Duration.ofSeconds(3)));

        Instant start = Instant.now();
        ResponseEntity<String> response = get("/api/v1/products");

        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(response.getBody()).contains("\"status\":504");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(2_500));
    }

    private ResponseEntity<String> get(String path) {
        return RestClient.create().get().uri("http://localhost:" + port + path)
                .retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(String.class);
    }

    private static ServerSocket hangsUpOnEveryone() {
        try {
            ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket connection = server.accept()) {
                        // closed at once, without a single byte of response
                    } catch (IOException e) {
                        // the server socket was closed: stop
                    }
                }
            });
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
