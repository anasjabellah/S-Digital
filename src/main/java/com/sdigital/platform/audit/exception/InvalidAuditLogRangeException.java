package com.sdigital.platform.audit.exception;

import java.time.Instant;

/**
 * Raised when the requested audit time range is inconsistent, i.e. when the lower
 * bound ({@code from}) is strictly after the upper bound ({@code to}).
 *
 * <p>The exception carries no business data beyond the two instants, and the HTTP layer
 * only exposes a generic, client-safe message.</p>
 */
public class InvalidAuditLogRangeException extends RuntimeException {

    private final Instant from;
    private final Instant to;

    public InvalidAuditLogRangeException(Instant from, Instant to) {
        super("'from' must not be after 'to'");
        this.from = from;
        this.to = to;
    }

    public Instant getFrom() {
        return from;
    }

    public Instant getTo() {
        return to;
    }
}
