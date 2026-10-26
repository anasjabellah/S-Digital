package com.sdigital.platform.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the database-integrity branch of {@link GlobalExceptionHandler}.
 *
 * <p>They drive the handler with the exception shape Spring Data JPA actually produces
 * (a {@link DataIntegrityViolationException} wrapping Hibernate's
 * {@link ConstraintViolationException} and the driver {@link SQLException}), so the
 * decision is based on the SQL state and the constraint name, exactly as in production.</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest(name = "{0} -> 409 \"{1}\"")
    @CsvSource({
            "uk_app_user_email,             Email already in use",
            "uk_app_user_keycloak_id,       User is already linked to a Keycloak account",
            "uk_agency_code,                Agency code already in use",
            "uk_function_code,              Function code already in use",
            "uk_email_template_code,        Email template code already in use"
    })
    @DisplayName("a known unique constraint maps to 409 with a stable, client-safe detail")
    void knownUniqueConstraintReturnsConflict(String constraintName, String expectedDetail) {
        ProblemDetail problem = handler.handleDataIntegrityViolation(integrity("23505", constraintName));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo(expectedDetail);
        assertThat(problem.getDetail())
                .doesNotContain(constraintName)
                .doesNotContainIgnoringCase("sql")
                .doesNotContain("duplicate key");
    }

    @Test
    @DisplayName("an unnamed unique violation still returns a generic 409")
    void unnamedUniqueConstraintReturnsGenericConflict() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(integrity("23505", null));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("A value that must be unique is already in use");
    }

    @Test
    @DisplayName("a unique violation without Hibernate wrapper is still recognised")
    void rawSqlUniqueViolationReturnsConflict() {
        SQLException sqlException = new SQLException("duplicate key value violates unique constraint", "23505");

        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("integrity violated", sqlException));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("A value that must be unique is already in use");
    }

    @Test
    @DisplayName("a foreign-key violation stays a generic 500, not a 409")
    void foreignKeyViolationReturnsInternalServerError() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(integrity("23503", "fk_app_user_agency"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(problem.getDetail()).doesNotContain("fk_app_user_agency");
    }

    @Test
    @DisplayName("an integrity failure without SQL state stays a generic 500")
    void integrityFailureWithoutSqlStateReturnsInternalServerError() {
        ProblemDetail problem = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("internal database detail"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(problem.getDetail()).doesNotContain("internal database detail");
    }

    private static DataIntegrityViolationException integrity(String sqlState, String constraintName) {
        SQLException sqlException = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\"", sqlState);
        ConstraintViolationException hibernateException =
                new ConstraintViolationException("could not execute statement", sqlException, constraintName);
        return new DataIntegrityViolationException("could not execute statement", hibernateException);
    }
}
