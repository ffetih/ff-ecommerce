package io.github.ffetih.ecommerce.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A tiny HTTP server standing in for a downstream service, built on the JDK's own HTTP server so the
 * tests need no extra library. Tests decide per request what to answer, and how slowly.
 */
public final class StubServer implements AutoCloseable {

    public record Request(String method, String path, String query) {
    }

    public record Response(int status, String jsonBody, Duration delay) {

        public static Response json(String body) {
            return new Response(200, body, Duration.ZERO);
        }

        public static Response status(int status) {
            return new Response(status, "{}", Duration.ZERO);
        }

        public Response after(Duration wait) {
            return new Response(status, jsonBody, wait);
        }
    }

    private final HttpServer server;
    private volatile Function<Request, Response> behaviour = request -> Response.status(404);

    private StubServer(HttpServer server) {
        this.server = server;
    }

    public static StubServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            StubServer stub = new StubServer(server);
            server.createContext("/", stub::handle);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start stub server", e);
        }
    }

    public void answer(Function<Request, Response> behaviour) {
        this.behaviour = behaviour;
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery());
            Response response = behaviour.apply(request);
            if (!response.delay().isZero()) {
                try {
                    Thread.sleep(response.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = response.jsonBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException clientGaveUp) {
                // The caller timed out and closed the connection: expected in the slow-dependency test.
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
