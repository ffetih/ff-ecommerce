package io.github.ffetih.ecommerce.customer.adapter.in.web;

import io.github.ffetih.ecommerce.customer.application.CustomerNotFoundException;
import io.github.ffetih.ecommerce.customer.application.EmailAlreadyRegisteredException;
import io.github.ffetih.ecommerce.customer.domain.CustomerDeactivatedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(CustomerNotFoundException.class)
    ProblemDetail notFound(CustomerNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler({EmailAlreadyRegisteredException.class, CustomerDeactivatedException.class})
    ProblemDetail conflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    /** Lost a race: the same email registered concurrently, or the customer was edited at the same time. */
    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail concurrentChange(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent change",
                "The request conflicts with a change made at the same time; read the current state and retry");
    }

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
