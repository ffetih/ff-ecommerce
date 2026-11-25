package io.github.ffetih.ecommerce.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in for Keycloak in integration tests: an OpenID Connect issuer that publishes a discovery
 * document and a JWK set over HTTP, and signs access tokens shaped like Keycloak's.
 *
 * <p>Services under test are configured exactly as in production ({@code issuer-uri} plus
 * {@code audiences}), so the tests exercise the real validation path: discovery, key retrieval,
 * signature, issuer, audience and expiry. Only end-to-end tests need the real Keycloak.
 *
 * <p>Clients registered with {@link #registerClient} can also get tokens from the token endpoint
 * (client credentials grant), the way a service gets its own token to call another service.
 */
public final class TestTokenIssuer implements AutoCloseable {

    private static final String REALM_PATH = "/realms/test";

    private record Client(String secret, List<String> audiences) {
    }

    private final HttpServer server;
    private final RSAKey signingKey;
    private volatile RSAKey unknownKey;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, List<String>> issued = new ConcurrentHashMap<>();
    private volatile Duration tokenDelay = Duration.ZERO;

    private TestTokenIssuer(HttpServer server, RSAKey signingKey) {
        this.server = server;
        this.signingKey = signingKey;
    }

    public static TestTokenIssuer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            TestTokenIssuer issuer = new TestTokenIssuer(server, newKey());
            server.createContext(REALM_PATH + "/.well-known/openid-configuration", issuer::discovery);
            server.createContext(REALM_PATH + "/protocol/openid-connect/certs", issuer::keys);
            server.createContext(REALM_PATH + "/protocol/openid-connect/token", issuer::tokenEndpoint);
            server.start();
            return issuer;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the test token issuer", e);
        }
    }

    /** The value for {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. */
    public String issuerUri() {
        return "http://localhost:" + server.getAddress().getPort() + REALM_PATH;
    }

    /** Starts a token for a user with the given subject; defaults: valid for 5 minutes, signed by this issuer. */
    public Token token(String subject) {
        return new Token(subject);
    }

    /** A token for a Keycloak service account: subject is random, the only role is {@code service}. */
    public Token serviceToken(String clientId) {
        return new Token(UUID.randomUUID().toString()).roles("service").claim("azp", clientId);
    }

    /**
     * Lets a confidential client get tokens with the client credentials grant, like a Keycloak
     * client with a service account. Its tokens carry the role {@code service} and the given audiences.
     */
    public TestTokenIssuer registerClient(String clientId, String secret, String... audiences) {
        clients.put(clientId, new Client(secret, List.of(audiences)));
        return this;
    }

    /** Makes the token endpoint answer this much later: an issuer that is overloaded or hanging. */
    public void delayTokenResponses(Duration delay) {
        this.tokenDelay = delay;
    }

    /** Every token the token endpoint has handed out to this client, oldest first. */
    public List<String> issuedTo(String clientId) {
        return List.copyOf(issued.getOrDefault(clientId, List.of()));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public final class Token {

        private final String subject;
        private String issuer = issuerUri();
        private List<String> audiences = List.of();
        private List<String> roles = List.of();
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private boolean signedByUnknownKey;
        private final Map<String, Object> extraClaims = new LinkedHashMap<>();

        private Token(String subject) {
            this.subject = subject;
        }

        public Token audiences(String... audiences) {
            this.audiences = List.of(audiences);
            return this;
        }

        public Token roles(String... roles) {
            this.roles = List.of(roles);
            return this;
        }

        public Token claim(String name, Object value) {
            extraClaims.put(name, value);
            return this;
        }

        public Token issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Token expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** Signs with a key that this issuer never published: a forged token. */
        public Token signedByUnknownKey() {
            this.signedByUnknownKey = true;
            return this;
        }

        public String sign() {
            Instant issuedAt = expiresAt.isBefore(Instant.now()) ? expiresAt.minus(Duration.ofMinutes(5)) : Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .jwtID(UUID.randomUUID().toString())
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audiences)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim("typ", "Bearer")
                    .claim("realm_access", Map.of("roles", roles));
            extraClaims.forEach(claims::claim);

            RSAKey key = signedByUnknownKey ? unknownKey() : signingKey;
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims.build());
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException("Could not sign the test token", e);
            }
            return jwt.serialize();
        }
    }

    private RSAKey unknownKey() {
        if (unknownKey == null) {
            // Same key id as the published key: only the signature can tell them apart.
            unknownKey = newKey(signingKey.getKeyID());
        }
        return unknownKey;
    }

    private void discovery(HttpExchange exchange) throws IOException {
        String issuer = issuerUri();
        respond(exchange, """
                {
                  "issuer": "%1$s",
                  "authorization_endpoint": "%1$s/protocol/openid-connect/auth",
                  "token_endpoint": "%1$s/protocol/openid-connect/token",
                  "jwks_uri": "%1$s/protocol/openid-connect/certs",
                  "response_types_supported": ["code"],
                  "subject_types_supported": ["public"],
                  "id_token_signing_alg_values_supported": ["RS256"]
                }
                """.formatted(issuer));
    }

    private void keys(HttpExchange exchange) throws IOException {
        respond(exchange, new JWKSet(signingKey.toPublicJWK()).toString());
    }

    /** Client credentials only; the client authenticates with HTTP Basic or with form fields. */
    private void tokenEndpoint(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String clientId = form.get("client_id");
        String secret = form.get("client_secret");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Basic ")) {
            String[] credentials = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8)
                    .split(":", 2);
            clientId = URLDecoder.decode(credentials[0], StandardCharsets.UTF_8);
            secret = credentials.length > 1 ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8) : null;
        }
        if (!"client_credentials".equals(form.get("grant_type"))) {
            respond(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            return;
        }
        Client client = clientId == null ? null : clients.get(clientId);
        if (client == null || !client.secret().equals(secret)) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        if (!tokenDelay.isZero()) {
            try {
                Thread.sleep(tokenDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
            return;
        }
        String token = serviceToken(clientId).audiences(client.audiences().toArray(String[]::new)).sign();
        issued.computeIfAbsent(clientId, id -> new CopyOnWriteArrayList<>()).add(token);
        respond(exchange, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":300}");
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        respond(exchange, 200, json);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        try (exchange) {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static RSAKey newKey() {
        return newKey(UUID.randomUUID().toString());
    }

    private static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate an RSA key", e);
        }
    }
}
