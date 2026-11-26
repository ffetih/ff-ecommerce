package io.github.ffetih.ecommerce.observability;

import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class ActuatorRequestsNotObservedTest {

    private final ActuatorRequestsNotObserved predicate = new ActuatorRequestsNotObserved();

    @AfterEach
    void unbindRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void healthChecksAndScrapesAreNotObserved() {
        assertThat(observed("/actuator/health/readiness")).isFalse();
        assertThat(observed("/actuator/prometheus")).isFalse();
        assertThat(observed("/actuator")).isFalse();
    }

    @Test
    void applicationRequestsAreObserved() {
        assertThat(observed("/api/v1/orders")).isTrue();
        assertThat(observed("/actuatorish")).isTrue();
    }

    @Test
    void observationsInsideAnActuatorRequestAreNotObservedEither() {
        bind(new MockHttpServletRequest("GET", "/actuator/health/liveness"));

        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isFalse();
    }

    @Test
    void observationsInsideAnApplicationRequestAreObserved() {
        bind(new MockHttpServletRequest("POST", "/api/v1/orders"));

        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isTrue();
    }

    @Test
    void observationsOutsideAnyRequestAreObserved() {
        // Kafka listeners and the outbox relay run on their own threads, with no request bound.
        assertThat(predicate.test("spring.kafka.listener", new Observation.Context())).isTrue();
    }

    @Test
    void theContextPathIsNotPartOfTheDecision() {
        var request = new MockHttpServletRequest("GET", "/shop/actuator/health");
        request.setContextPath("/shop");

        assertThat(predicate.test("http.server.requests",
                new ServerRequestObservationContext(request, new MockHttpServletResponse()))).isFalse();
    }

    private boolean observed(String path) {
        var request = new MockHttpServletRequest("GET", path);
        return predicate.test("http.server.requests",
                new ServerRequestObservationContext(request, new MockHttpServletResponse()));
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
