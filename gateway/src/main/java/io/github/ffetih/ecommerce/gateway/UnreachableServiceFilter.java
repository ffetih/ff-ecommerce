package io.github.ffetih.ecommerce.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * A service that cannot be reached is not an error of the gateway. Without this filter such a request
 * ends as a 500, which sends whoever is on call looking at the wrong component.
 * <ul>
 *   <li>no answer in time, whether no connection within {@code spring.http.clients.connect-timeout}
 *       or no response within {@code read-timeout}: 504 Gateway Timeout;</li>
 *   <li>any other failure on the way to the service (connection refused, connection dropped, no
 *       valid response): 502 Bad Gateway.</li>
 * </ul>
 * The same split nginx makes. The proxy's HTTP client reports failures of the call to the service as
 * a {@link ResourceAccessException} or a plain {@link IOException}. (An I/O error towards a client
 * that has gone away would also match, but then nobody receives the status anyway.)
 * The body is a problem response like every other error of the API, and names no host or port.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class UnreachableServiceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            HttpStatus status = statusFor(e);
            if (status == null || response.isCommitted()) {
                throw e;
            }
            response.resetBuffer();
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            String detail = status == HttpStatus.GATEWAY_TIMEOUT
                    ? "The service behind this path did not answer in time."
                    : "The service behind this path is not reachable.";
            response.getOutputStream().write(("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                    + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail
                    + "\",\"instance\":\"" + request.getRequestURI().replace("\"", "%22") + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static HttpStatus statusFor(Throwable failure) {
        boolean callToService = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            callToService |= cause instanceof ResourceAccessException || cause instanceof IOException;
        }
        return callToService ? HttpStatus.BAD_GATEWAY : null;
    }
}
