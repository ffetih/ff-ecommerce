package io.github.ffetih.ecommerce.security;

import io.github.ffetih.ecommerce.testsupport.TestTokenIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real HTTP server with real signed tokens. The service is configured as in production
 * (issuer URI and audience); the issuer is the in-process {@link TestTokenIssuer}.
 */
@SpringBootTest(classes = SecurityTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceServerIT {

    /** Lives as long as the test JVM, like the shared containers. */
    static final TestTokenIssuer issuer = TestTokenIssuer.start();

    static final String ALICE = "a11ce000-0000-4000-8000-000000000001";

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add(ResourceServerSecurity.ISSUER_URI_PROPERTY, issuer::issuerUri);
    }

    @Value("${local.server.port}")
    int port;

    record Response(int status, String body, HttpHeaders headers) {

        String challenge() {
            return headers.getFirst(HttpHeaders.WWW_AUTHENTICATE);
        }

        String contentType() {
            return headers.getFirst(HttpHeaders.CONTENT_TYPE);
        }
    }

    @Test
    void anonymousCallersGetABearerChallengeAndAProblemBody() {
        Response response = get("/api/test/whoami", null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.challenge()).startsWith("Bearer");
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"status\":401").contains("\"instance\":\"/api/test/whoami\"");
    }

    @Test
    void aValidTokenAuthenticatesItsSubjectWithItsRealmRoles() {
        Response response = get("/api/test/whoami", customer());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"subject\":\"" + ALICE + "\"").contains("ROLE_customer");
    }

    @Test
    void rejectsATokenIssuedForAnotherService() {
        String token = issuer.token(ALICE).audiences("shipping-service").roles("customer").sign();

        assertInvalidToken(get("/api/test/whoami", token));
    }

    @Test
    void rejectsATokenWithoutAnAudience() {
        assertInvalidToken(get("/api/test/whoami", issuer.token(ALICE).roles("customer").sign()));
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        String token = issuer.token(ALICE).audiences("test-service").issuer("http://evil.example/realms/test").sign();

        assertInvalidToken(get("/api/test/whoami", token));
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = issuer.token(ALICE).audiences("test-service")
                .expiresAt(Instant.now().minus(10, ChronoUnit.MINUTES)).sign();

        assertInvalidToken(get("/api/test/whoami", token));
    }

    @Test
    void rejectsAForgedSignature() {
        String token = issuer.token(ALICE).audiences("test-service").roles("admin").signedByUnknownKey().sign();

        assertInvalidToken(get("/api/test/admin", token));
    }

    @Test
    void rejectsSomethingThatIsNotAJwt() {
        assertInvalidToken(get("/api/test/whoami", "not-a-jwt"));
    }

    @Test
    void anAuthenticatedCallerWithoutTheRoleIsForbidden() {
        Response response = get("/api/test/admin", customer());

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"status\":403");
    }

    @Test
    void theRoleOpensTheEndpoint() {
        String admin = issuer.token("ad000000-0000-4000-8000-000000000003").audiences("test-service").roles("admin").sign();

        assertThat(get("/api/test/admin", admin).status()).isEqualTo(200);
    }

    @Test
    void anEndpointWithoutARuleIsClosedToEveryone() {
        String admin = issuer.token("ad000000-0000-4000-8000-000000000003").audiences("test-service").roles("admin").sign();

        assertThat(get("/api/test/unlisted", null).status()).isEqualTo(401);
        assertThat(get("/api/test/unlisted", customer()).status()).isEqualTo(403);
        assertThat(get("/api/test/unlisted", admin).status()).isEqualTo(403);
    }

    @Test
    void metricsAreForOperatorsOnly() {
        String admin = issuer.token("ad000000-0000-4000-8000-000000000003").audiences("test-service").roles("admin").sign();

        for (String path : new String[] {"/actuator/metrics", "/actuator/metrics/jvm.threads.live"}) {
            assertThat(get(path, null).status()).as("anonymous %s", path).isEqualTo(401);
            assertThat(get(path, customer()).status()).as("customer %s", path).isEqualTo(403);
            assertThat(get(path, admin).status()).as("admin %s", path).isEqualTo(200);
        }
    }

    @Test
    void healthStaysOpenForProbes() {
        assertThat(get("/actuator/health", null).status()).isEqualTo(200);
    }

    @Test
    void anOpenEndpointNeedsNoToken() {
        assertThat(get("/api/test/open", null).status()).isEqualTo(200);
    }

    /**
     * The old gateway decided access by comparing path strings. Every spelling of the admin path
     * must be either rejected or matched by the same rule; none may reach the endpoint.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/test/admin/",
            "/api/test/ADMIN",
            "/api/test//admin",
            "/api/test/./admin",
            "/api/test/x/../admin",
            "/api/test/admin;jsessionid=1",
            "/api/test/%61dmin",
            "/api/test/admin%2F",
    })
    void noSpellingOfAProtectedPathGetsPastTheRules(String path) {
        Response response = get(path, customer());

        assertThat(response.status()).as("GET %s as a customer", path).isIn(400, 403, 404);
    }

    private String customer() {
        return issuer.token(ALICE).audiences("test-service").roles("customer").sign();
    }

    private static void assertInvalidToken(Response response) {
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.challenge()).contains("error=\"invalid_token\"");
        assertThat(response.body()).contains("\"status\":401");
    }

    private Response get(String rawPath, String token) {
        var request = RestClient.create().get().uri(URI.create("http://localhost:" + port + rawPath));
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        ResponseEntity<String> response = request.retrieve()
                .onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        return new Response(response.getStatusCode().value(), response.getBody(), response.getHeaders());
    }
}
