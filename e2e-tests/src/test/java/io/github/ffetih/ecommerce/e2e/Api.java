package io.github.ffetih.ecommerce.e2e;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * Minimal JSON-over-HTTP client for the tests: every status is returned, never thrown. Calls carry
 * the given bearer token, or none.
 */
final class Api {

    record Response(int status, JsonNode body) {

        String text(String field) {
            return body.get(field).asString();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;

    Api(String baseUrl) {
        this(baseUrl, null);
    }

    Api(String baseUrl, String bearerToken) {
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> {
                    if (bearerToken != null) {
                        headers.setBearerAuth(bearerToken);
                    }
                })
                .build();
    }

    Response get(String path) {
        return call(HttpMethod.GET, path, Map.of(), null);
    }

    Response post(String path, String json) {
        return call(HttpMethod.POST, path, Map.of(), json);
    }

    Response post(String path, Map<String, String> headers, String json) {
        return call(HttpMethod.POST, path, headers, json);
    }

    private Response call(HttpMethod method, String path, Map<String, String> headers, String json) {
        RestClient.RequestBodySpec request = rest.method(method).uri(path)
                .headers(httpHeaders -> headers.forEach(httpHeaders::add));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(json);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        String body = response.getBody();
        return new Response(response.getStatusCode().value(), body == null || body.isBlank() ? null : JSON.readTree(body));
    }
}
