package io.github.ffetih.ecommerce.order.adapter.in.web;

import io.github.ffetih.ecommerce.order.application.DownstreamUnavailableException;
import io.github.ffetih.ecommerce.order.application.IdempotencyKeyReusedException;
import io.github.ffetih.ecommerce.order.application.NotAllowedException;
import io.github.ffetih.ecommerce.order.application.OrderNotFoundException;
import io.github.ffetih.ecommerce.order.application.OrderRejectedException;
import io.github.ffetih.ecommerce.order.application.ReturnNotFoundException;
import io.github.ffetih.ecommerce.order.domain.IllegalReturnTransitionException;
import io.github.ffetih.ecommerce.order.domain.ReturnNotAllowedException;
import io.github.ffetih.ecommerce.order.domain.IllegalOrderTransitionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler({OrderNotFoundException.class, ReturnNotFoundException.class})
    ProblemDetail notFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler(NotAllowedException.class)
    ProblemDetail notAllowed(NotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage());
    }

    /** Well-formed request that the business rules refuse (unknown product, deactivated customer...). */
    @ExceptionHandler(OrderRejectedException.class)
    ProblemDetail rejected(OrderRejectedException ex) {
        return problem(HttpStatusCode.valueOf(422), "Order rejected", ex.getMessage());
    }

    /** Well-formed return request that the rules refuse: too late, not paid, more than was bought. */
    @ExceptionHandler(ReturnNotAllowedException.class)
    ProblemDetail returnRefused(ReturnNotAllowedException ex) {
        return problem(HttpStatusCode.valueOf(422), "Return refused", ex.getMessage());
    }

    @ExceptionHandler({IdempotencyKeyReusedException.class, IllegalOrderTransitionException.class,
            IllegalReturnTransitionException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail conflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /** A dependency is down or slow: tell the client when to retry (with the same idempotency key). */
    @ExceptionHandler(DownstreamUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(DownstreamUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable",
                        ex.getMessage() + "; retry with the same Idempotency-Key"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatusCode status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
