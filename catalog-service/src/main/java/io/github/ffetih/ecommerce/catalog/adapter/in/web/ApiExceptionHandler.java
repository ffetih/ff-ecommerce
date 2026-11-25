package io.github.ffetih.ecommerce.catalog.adapter.in.web;

import io.github.ffetih.ecommerce.catalog.application.ConflictException;
import io.github.ffetih.ecommerce.catalog.application.NotFoundException;
import io.github.ffetih.ecommerce.catalog.domain.InsufficientStockException;
import io.github.ffetih.ecommerce.catalog.domain.InvalidReservationStateException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps business outcomes to RFC 9457 problem responses. Anything not listed here is a bug and
 * stays a 500, including broken stock invariants ({@link IllegalStateException}).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail insufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Insufficient stock", ex.getMessage());
        problem.setProperty("sku", ex.sku().value());
        problem.setProperty("requested", ex.requested());
        problem.setProperty("available", ex.available());
        return problem;
    }

    @ExceptionHandler({ConflictException.class, InvalidReservationStateException.class})
    ProblemDetail conflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /** A concurrent request won a race on a unique key (e.g. the same product created twice at once). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail concurrentChange(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent change",
                "The request conflicts with a change made at the same time; read the current state and retry");
    }

    /** Domain value validation, e.g. an invalid SKU, currency or amount precision. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidInput(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
