package com.sdigital.platform.common.exception;

import com.sdigital.platform.agency.exception.AgencyCodeAlreadyUsedException;
import com.sdigital.platform.agency.exception.AgencyNotFoundException;
import com.sdigital.platform.audit.exception.InvalidAuditLogRangeException;
import com.sdigital.platform.email.exception.EmailTemplateCodeAlreadyUsedException;
import com.sdigital.platform.email.exception.EmailTemplateNotFoundException;
import com.sdigital.platform.email.exception.InvalidEmailTemplateException;
import com.sdigital.platform.function.exception.FunctionCodeAlreadyUsedException;
import com.sdigital.platform.function.exception.FunctionNotFoundException;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserAgencyNotFoundException;
import com.sdigital.platform.user.exception.UserFunctionNotFoundException;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.exception.UserProvisioningException;
import com.sdigital.platform.user.keycloak.exception.KeycloakIdentityAlreadyExistsException;
import com.sdigital.platform.user.keycloak.exception.KeycloakIntegrationException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUserNotFoundException;
import com.sdigital.platform.user.keycloak.exception.RoleChangeDeniedException;
import com.sdigital.platform.user.keycloak.exception.RoleNotAllowedException;
import com.sdigital.platform.user.keycloak.exception.UserNotLinkedToKeycloakException;
import com.sdigital.platform.notification.exception.NotificationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Map;

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
    private static final URI TYPE_UNPROCESSABLE = URI.create("https://s-digital.io/errors/unprocessable");
    private static final URI TYPE_FORBIDDEN = URI.create("https://s-digital.io/errors/forbidden");
    private static final URI TYPE_SERVICE_UNAVAILABLE = URI.create("https://s-digital.io/errors/service-unavailable");

    /**
     * Client-safe detail per known unique constraint, aligned with the deterministic
     * duplicate-conflict messages returned by the business services.
     */
    private static final Map<String, String> UNIQUE_CONSTRAINT_DETAILS = Map.of(
            "uk_app_user_email", "Email already in use",
            "uk_app_user_keycloak_id", "User is already linked to a Keycloak account",
            "uk_agency_code", "Agency code already in use",
            "uk_function_code", "Function code already in use",
            "uk_email_template_code", "Email template code already in use");

    /**
     * Used when the database reports a uniqueness violation without naming the constraint.
     */
    private static final String GENERIC_UNIQUE_DETAIL = "A value that must be unique is already in use";

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

    /**
     * Inconsistent time range requested on the audit listing (for example
     * {@code from} strictly after {@code to}). The detail stays generic and client-safe.
     */
    @ExceptionHandler(InvalidAuditLogRangeException.class)
    public ProblemDetail handleInvalidAuditLogRange(InvalidAuditLogRangeException ex) {
        log.debug("Request argument rejected: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION,
                "Validation failed", "Invalid date range: 'from' must not be after 'to'");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "User not found");
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ProblemDetail handleNotificationNotFound(NotificationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Notification not found");
    }

    /**
     * Database integrity failure.
     *
     * <p>A uniqueness violation means the application-level duplicate check lost a
     * check-then-act race: the request is a conflict (409), exactly like the deterministic
     * duplicate rejection. The detail is chosen from the violated constraint and never
     * echoes the SQL, the constraint definition or the conflicting value.</p>
     *
     * <p>Any other integrity failure (foreign key, not-null, check) is unexpected and stays
     * a generic 500 so a real server-side problem is not disguised as a user conflict.</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        if (DatabaseConstraints.isUniqueViolation(ex)) {
            String constraintName = DatabaseConstraints.uniqueConstraintName(ex).orElse(null);
            log.debug("Unique constraint violation rejected: constraint={}",
                    constraintName == null ? "unknown" : constraintName);
            String detail = constraintName == null
                    ? GENERIC_UNIQUE_DETAIL
                    : UNIQUE_CONSTRAINT_DETAILS.getOrDefault(constraintName, GENERIC_UNIQUE_DETAIL);
            return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", detail);
        }

        log.error("Unexpected data integrity violation", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL,
                "Internal server error", "An unexpected error occurred");
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ProblemDetail handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", "Email already in use");
    }

    /**
     * A remote Keycloak identity already owns the requested username/email. A business
     * conflict, not an outage; the detail is generic and never echoes the email.
     */
    @ExceptionHandler(KeycloakIdentityAlreadyExistsException.class)
    public ProblemDetail handleKeycloakIdentityAlreadyExists(KeycloakIdentityAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict",
                "A Keycloak account already exists for this email");
    }

    /**
     * Provisioning failed after the remote identity was created (local persistence/linking
     * failure) or Keycloak returned an unusable id. Only the coarse reason and the cause
     * class name are logged; no email, Keycloak id, token, secret or response body is
     * exposed or written to the log.
     */
    @ExceptionHandler(UserProvisioningException.class)
    public ProblemDetail handleUserProvisioning(UserProvisioningException ex) {
        log.error("User provisioning failed: reason={} cause={}", ex.getReason(),
                ex.getCause() == null ? null : ex.getCause().getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL,
                "Internal server error", "An unexpected error occurred");
    }

    @ExceptionHandler(UserAgencyNotFoundException.class)
    public ProblemDetail handleUserAgencyNotFound(UserAgencyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Agency not found");
    }

    @ExceptionHandler(AgencyNotFoundException.class)
    public ProblemDetail handleAgencyNotFound(AgencyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Agency not found");
    }

    @ExceptionHandler(UserFunctionNotFoundException.class)
    public ProblemDetail handleUserFunctionNotFound(UserFunctionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Function not found");
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

    @ExceptionHandler(EmailTemplateNotFoundException.class)
    public ProblemDetail handleEmailTemplateNotFound(EmailTemplateNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "Email template not found");
    }

    @ExceptionHandler(EmailTemplateCodeAlreadyUsedException.class)
    public ProblemDetail handleEmailTemplateCodeAlreadyUsed(EmailTemplateCodeAlreadyUsedException ex) {
        return problem(HttpStatus.CONFLICT, TYPE_CONFLICT, "Conflict", "Email template code already in use");
    }

    @ExceptionHandler(InvalidEmailTemplateException.class)
    public ProblemDetail handleInvalidEmailTemplate(InvalidEmailTemplateException ex) {
        log.debug("Email template rejected: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, TYPE_VALIDATION,
                "Validation failed", "Invalid email template placeholders");
    }

    /**
     * Local user not linked to Keycloak: the request is understood but cannot be
     * processed until the account is linked.
     */
    @ExceptionHandler(UserNotLinkedToKeycloakException.class)
    public ProblemDetail handleUserNotLinked(UserNotLinkedToKeycloakException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, TYPE_UNPROCESSABLE,
                "Unprocessable entity", "User is not linked to a Keycloak account");
    }

    /**
     * Target Keycloak account no longer exists. Distinct from the local 404 but
     * presented with the same status so no account detail is leaked.
     */
    @ExceptionHandler(KeycloakUserNotFoundException.class)
    public ProblemDetail handleKeycloakUserNotFound(KeycloakUserNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, TYPE_NOT_FOUND, "Not found", "User not found");
    }

    /**
     * A role change refused by business policy (reserved role, self-modification,
     * allowlist, disabled account or missing configuration). The detail stays generic.
     */
    @ExceptionHandler(RoleChangeDeniedException.class)
    public ProblemDetail handleRoleChangeDenied(RoleChangeDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, TYPE_FORBIDDEN, "Forbidden", "Role change is not allowed");
    }

    /**
     * Unknown or invalid role name.
     */
    @ExceptionHandler(RoleNotAllowedException.class)
    public ProblemDetail handleRoleNotAllowed(RoleNotAllowedException ex) {
        return problem(HttpStatus.BAD_REQUEST, TYPE_BAD_REQUEST, "Bad request", "Invalid role");
    }

    /**
     * Keycloak Admin integration unavailable or not configured. Never exposes the
     * underlying cause.
     */
    @ExceptionHandler(KeycloakUnavailableException.class)
    public ProblemDetail handleKeycloakUnavailable(KeycloakUnavailableException ex) {
        log.warn("Keycloak admin integration unavailable: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TYPE_SERVICE_UNAVAILABLE,
                "Service unavailable", "Keycloak is temporarily unavailable");
    }

    /**
     * The backend's own Keycloak integration is misconfigured or not authorized.
     * Reported as a server-side failure (500) rather than as a transient outage, so a
     * bad service-account configuration is not masked as unavailability. No Keycloak
     * detail is exposed.
     */
    @ExceptionHandler(KeycloakIntegrationException.class)
    public ProblemDetail handleKeycloakIntegration(KeycloakIntegrationException ex) {
        log.error("Keycloak admin integration misconfigured: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, TYPE_INTERNAL,
                "Internal server error", "An unexpected error occurred");
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
