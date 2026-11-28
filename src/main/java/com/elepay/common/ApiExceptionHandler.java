package com.elepay.common;

import jakarta.validation.ConstraintViolationException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice(basePackages = "com.elepay")
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Environment env;

    @Autowired
    ApiExceptionHandler(@Autowired(required = false) Environment env) {
        this.env = env;
    }

    ApiExceptionHandler() {
        this(null);
    }

    private boolean isProd() {
        return env != null && env.acceptsProfiles(Profiles.of("prod", "production"))
                && !env.acceptsProfiles(Profiles.of("test", "dev", "sandbox"));
    }

    private static String defaultReasonPhrase(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Request failed";
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(error.status(), error.getMessage());
        problem.setProperty("code", error.code());
        return problem;
    }

    @ExceptionHandler(ErrorResponseException.class)
    ProblemDetail errorResponse(ErrorResponseException error) {
        HttpStatusCode status = error.getStatusCode();
        String rawDetail = error.getBody().getDetail();
        String detail = (!isProd() && rawDetail != null && !rawDetail.isBlank())
                ? rawDetail
                : defaultReasonPhrase(status);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", "request_error");
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException error) {
        log.warn("Malformed request body: {}", error.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
        problem.setProperty("code", "malformed_request");
        return problem;
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    ProblemDetail missingBinding(ServletRequestBindingException error) {
        log.warn("Request binding error: {}", error.getMessage());
        String detail = (!isProd() && error.getMessage() != null && !error.getMessage().isBlank())
                ? error.getMessage()
                : "Missing required request parameter or header";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", "missing_parameter");
        return problem;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException error) {
        log.warn("Parameter type mismatch for '{}': {}", error.getName(), error.getMessage());
        String detail = !isProd()
                ? "Invalid value for parameter '" + error.getName() + "'"
                : "Invalid request parameter";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", "invalid_parameter");
        return problem;
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type");
        problem.setProperty("code", "unsupported_media_type");
        return problem;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail methodNotAllowed(HttpRequestMethodNotSupportedException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not allowed");
        problem.setProperty("code", "method_not_allowed");
        return problem;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail noResource(NoResourceFoundException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Resource not found");
        problem.setProperty("code", "not_found");
        return problem;
    }

    /** Rethrow Spring Security authorization errors so AccessDeniedHandler returns 403. */
    @ExceptionHandler(AccessDeniedException.class)
    void accessDenied(AccessDeniedException error) {
        throw error;
    }

    /** Rethrow Spring Security authentication errors so AuthenticationEntryPoint returns 401. */
    @ExceptionHandler(AuthenticationException.class)
    void authentication(AuthenticationException error) {
        throw error;
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
        log.warn("Invalid request argument: {}", error.getMessage());
        String detail = (!isProd() && error.getMessage() != null && !error.getMessage().isBlank())
                ? error.getMessage() : "Invalid request argument";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", "invalid_argument");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException error) {
        log.warn("Invalid request state: {}", error.getMessage());
        String detail = (!isProd() && error.getMessage() != null && !error.getMessage().isBlank())
                ? error.getMessage() : "The request conflicts with current state";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setProperty("code", "invalid_state");
        return problem;
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException error) {
        log.warn("Resource not found: {}", error.getMessage());
        String detail = (!isProd() && error.getMessage() != null && !error.getMessage().isBlank())
                ? error.getMessage() : "Resource not found";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problem.setProperty("code", "not_found");
        return problem;
    }

    /** Database constraints are the last line of defence; never leak SQL details. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException error) {
        log.warn("Data integrity violation", error);
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

    /** Catch-all handler: never leak internal exception messages or stack traces. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error) {
        if (error instanceof AccessDeniedException ade) {
            throw ade;
        }
        if (error instanceof AuthenticationException ae) {
            throw ae;
        }
        if (error instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            log.warn("Spring MVC error response ({}): {}", status.value(), error.getMessage());
            String rawDetail = er.getBody().getDetail();
            String detail = (!isProd() && rawDetail != null && !rawDetail.isBlank())
                    ? rawDetail
                    : defaultReasonPhrase(status);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
            problem.setProperty("code", "request_error");
            return problem;
        }
        log.error("Unhandled exception in API request", error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An internal error occurred");
        problem.setProperty("code", "internal_error");
        return problem;
    }
}
