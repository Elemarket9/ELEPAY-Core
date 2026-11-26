package com.elepay.common;

import jakarta.validation.ConstraintViolationException;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.elepay")
class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        problem.setProperty("code", error.code());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage()).toList());
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", error.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).toList());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException error) {
        String detail = error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage() : "Invalid request argument";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", "invalid_argument");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException error) {
        String detail = error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage() : "The request conflicts with current state";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setProperty("code", "invalid_state");
        return problem;
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException error) {
        String detail = error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage() : "Resource not found";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setProperty("code", "not_found");
        return problem;
    }

    /** Database constraints are the last line of defence; never leak SQL details. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with current state");
        problem.setProperty("code", "state_conflict");
        return problem;
    }

    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    ProblemDetail busy(org.springframework.dao.ConcurrencyFailureException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Concurrent update; retry with the same idempotency key");
        problem.setProperty("code", "retry");
        return problem;
    }
}
