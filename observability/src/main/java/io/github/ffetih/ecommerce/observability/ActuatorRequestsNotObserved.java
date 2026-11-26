package io.github.ffetih.ecommerce.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Nothing that happens while serving {@code /actuator/**} is observed: no span, no
 * {@code http.server.requests} sample. In Kubernetes the kubelet calls the health endpoints every few
 * seconds and Prometheus scrapes every 15 seconds; observed, those calls outnumber real traffic in the
 * request rate and latency, and fill Jaeger with traces nobody reads.
 * <p>
 * The HTTP observation carries its request. Observations started inside it (Spring Security's filter
 * chain, for one) don't; for those the request comes from {@link RequestContextHolder}, which is bound
 * before the security filters run. Outside a request (Kafka listeners, the outbox relay) there is none,
 * and everything is observed.
 */
public final class ActuatorRequestsNotObserved implements ObservationPredicate {

    static final String ACTUATOR = "/actuator";

    @Override
    public boolean test(String name, Observation.Context context) {
        HttpServletRequest request = context instanceof ServerRequestObservationContext http
                ? http.getCarrier()
                : currentRequest();
        return request == null || !isActuator(request);
    }

    private static boolean isActuator(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/");
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
