package io.github.ffetih.ecommerce.e2e;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Gets real access tokens from the Keycloak of the platform, for the users and clients of the realm. */
final class Tokens {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest = RestClient.create();
    private final String tokenEndpoint;

    Tokens(String issuerUri) {
        this.tokenEndpoint = issuerUri + "/protocol/openid-connect/token";
    }

    /** A realm user's token through the dev-only password-grant client; the password is the username. */
    String user(String username) {
        return request("grant_type", "password", "client_id", "ecommerce-dev", "username", username, "password", username);
    }

    /** A service account's token (client credentials), as a service would get it. */
    String service(String clientId, String secret) {
        return request("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret);
    }

    private String request(String... form) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        for (int i = 0; i < form.length; i += 2) {
            body.add(form[i], form[i + 1]);
        }
        String response = rest.post().uri(tokenEndpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(String.class);
        return JSON.readTree(response).get("access_token").asString();
    }
}
