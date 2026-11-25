package io.github.ffetih.ecommerce.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 401 and 403 as RFC 9457 problem responses, like every other error of the API.
 *
 * <p>The bearer-token handlers run first: they set the status and the RFC 6750
 * {@code WWW-Authenticate} header, which carries the technical reason (for example
 * {@code error="invalid_token", error_description="The aud claim is not valid"}). The body stays
 * generic and the same for every reason.
 */
final class ProblemResponses {

    private final JsonMapper json;

    ProblemResponses(JsonMapper json) {
        this.json = json;
    }

    AuthenticationEntryPoint authenticationEntryPoint() {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception);
            write(request, response, HttpStatus.UNAUTHORIZED, "A valid bearer token is required.");
        };
    }

    AccessDeniedHandler accessDeniedHandler() {
        var bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            bearer.handle(request, response, exception);
            write(request, response, HttpStatus.FORBIDDEN, "You are not allowed to perform this operation.");
        };
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(json.writeValueAsBytes(problem));
    }
}
