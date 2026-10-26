package com.sdigital.platform.common.exception;

import org.hibernate.exception.ConstraintViolationException;

import java.sql.SQLException;
import java.util.Optional;

/**
 * Read-only inspection of a persistence failure to decide whether it is a database
 * uniqueness violation.
 *
 * <p>Application-level duplicate checks ({@code existsBy...}) are check-then-act: two
 * concurrent requests can both pass the check and only the database constraint stops the
 * second write. Such a failure is a business conflict (HTTP 409), not a server error.</p>
 *
 * <p>Everything else that violates integrity — a foreign key, a not-null or a check
 * constraint — is an unexpected condition and must stay a 500. That is why the decision is
 * based on the SQL state of the underlying driver exception and never on the exception
 * type alone.</p>
 *
 * <p>Only metadata is inspected (SQL state and constraint name); messages, parameter
 * values and any other database detail stay internal and are never returned to a client.</p>
 */
public final class DatabaseConstraints {

    /**
     * ANSI SQL state for {@code unique_violation} (also used by PostgreSQL).
     */
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private DatabaseConstraints() {
    }

    /**
     * Returns {@code true} when the failure is a uniqueness violation reported by the
     * database, whatever the nesting of the cause chain.
     */
    public static boolean isUniqueViolation(Throwable throwable) {
        return findUniqueViolation(throwable).isPresent();
    }

    /**
     * Returns the name of the violated unique constraint when the database driver or
     * Hibernate exposes it.
     */
    public static Optional<String> uniqueConstraintName(Throwable throwable) {
        return findUniqueViolation(throwable)
                .filter(ConstraintViolationException.class::isInstance)
                .map(ConstraintViolationException.class::cast)
                .map(ConstraintViolationException::getConstraintName)
                .filter(name -> !name.isBlank());
    }

    private static Optional<ConstraintViolationException> findUniqueViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && hasUniqueSqlState(constraintViolation.getSQLException())) {
                return Optional.of(constraintViolation);
            }
            if (current instanceof SQLException sqlException && hasUniqueSqlState(sqlException)) {
                // Unique violation reported by the driver without a Hibernate wrapper: the
                // conflict still has to be recognised, only the constraint name is unknown.
                return Optional.of(new ConstraintViolationException(
                        "unique violation", sqlException, null));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static boolean hasUniqueSqlState(SQLException sqlException) {
        SQLException current = sqlException;
        while (current != null) {
            if (UNIQUE_VIOLATION_SQL_STATE.equals(current.getSQLState())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }
}
