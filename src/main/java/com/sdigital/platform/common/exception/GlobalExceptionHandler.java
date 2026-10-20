package com.sdigital.platform.common.exception;

import com.sdigital.platform.agency.exception.AgencyCodeAlreadyUsedException;
import com.sdigital.platform.agency.exception.AgencyNotFoundException;
import com.sdigital.platform.function.exception.FunctionCodeAlreadyUsedException;
import com.sdigital.platform.function.exception.FunctionNotFoundException;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

/**
 * Centralized translation of exceptions into RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Internal messages (identifiers, emails, stack traces) are never exposed: each
 * handler builds a generic, client-safe detail.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI TYPE_VALIDATION = URI.create("https://s-digital.io/errors/validation");
    private static final URI TYPE_BAD_REQUEST = URI.create("https://s-digital.io/errors/bad-request");
    private static final URI TYPE_NOT_FOUND = URI.create("https://s-digital.io/errors/not-found");
    private static final URI TYPE_CONFLICT = URI.create("https://s-digital.io/errors/conflict");
    private static final URI TYPE_INTERNAL = URI.create("https://s-digital.io/errors/internal");

    /**
     * Bean Validation failures on request bodies ({@code @Valid}).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ValidationError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION,
                "Validation failed", "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Malformed or unreadable JSON body.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMessageNotReadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST,
                "Bad request", "Malformed or unreadable request body");
    }

    /**
     * Wrong parameter type, e.g. a malformed UUID in the path.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST,
                "Bad request", "Invalid value for parameter '" + ex.getName() + "'");
    }

    /**
     * Unknown sort property (thrown by Spring Data when the query is built, and by the
     * explicit whitelist of the user listing).
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handlePropertyReference(PropertyReferenceException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION,
                "Validation failed", "Invalid sort property: " + ex.getPropertyName());
    }

    /**
     * Invalid request parameter value raised during argument resolution, e.g. an unknown
     * sort direction in {@code sort=email,BOGUS}. Mapped to 400 so that no invalid
     * parameter ever surfaces as a 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.debug("Request argument rejected: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST,
                "Bad request", "Invalid request parameter");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "User not found");
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ProblemDetail handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", "Email already in use");
    }

    @ExceptionHandler(AgencyNotFoundException.class)
    public ProblemDetail handleAgencyNotFound(AgencyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Agency not found");
    }

    @ExceptionHandler(AgencyCodeAlreadyUsedException.class)
    public ProblemDetail handleAgencyCodeAlreadyUsed(AgencyCodeAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", "Agency code already in use");
    }

    @ExceptionHandler(FunctionNotFoundException.class)
    public ProblemDetail handleFunctionNotFound(FunctionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Function not found");
    }

    @ExceptionHandler(FunctionCodeAlreadyUsedException.class)
    public ProblemDetail handleFunctionCodeAlreadyUsed(FunctionCodeAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", "Function code already in use");
    }

    /**
     * Fallback for anything unexpected.
     *
     * <p>Exceptions that already carry a proper HTTP status through Spring's
     * {@link ErrorResponse} contract keep that status (so framework errors such as
     * 404/405/415 are not turned into 500). Everything else becomes a generic 500
     * without leaking internal details.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            ProblemDetail problem = errorResponse.getBody();
            if (problem == null) {
                problem = ProblemDetail.forStatus(errorResponse.getStatusCode());
            }
            return ResponseEntity.status(errorResponse.getStatusCode()).body(problem);
        }

        log.error("Unexpected error while handling request", ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL,
                "Internal server error", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(type);
        return problem;
    }

    /**
     * Field-level validation error exposed in the {@code errors} property.
     */
    public record ValidationError(String field, String message) {
    }
}
