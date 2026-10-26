package com.sdigital.platform.audit.dto;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.exception.InvalidAuditLogRangeException;

import java.time.Instant;
import java.util.UUID;

/**
 * Query criteria of the audit listing.
 *
 * <p>Every criterion is optional: a {@code null} value means "no filter on this
 * dimension". The criteria are normalized on construction, so an instance is always
 * valid and can be applied to a query as is:</p>
 * <ul>
 *     <li>a blank textual criterion is treated as absent;</li>
 *     <li>a textual criterion is trimmed and cannot exceed the length of the
 *     corresponding database column (64 characters);</li>
 *     <li>the time range is rejected when {@code from} is strictly after {@code to}.</li>
 * </ul>
 *
 * <p>The time bounds are <b>inclusive</b>: an event whose {@code occurredAt} is exactly
 * {@code from} or exactly {@code to} is part of the result.</p>
 *
 * <p>{@code action} and {@code entityType} are matched exactly (case-sensitive) against
 * the stable codes written by the application. {@code entityId} is the identifier of the
 * affected resource, which is a UUID for every audited entity type.</p>
 */
public record AuditLogFilter(

        String action,

        String entityType,

        UUID entityId,

        UUID actorUserId,

        AuditOutcome outcome,

        Instant from,

        Instant to
) {

    /**
     * Maximum length of the textual criteria, matching the {@code VARCHAR(64)} columns
     * of the {@code audit_log} table.
     */
    private static final int MAX_TEXT_LENGTH = 64;

    /**
     * Normalizes and validates the criteria.
     *
     * @throws IllegalArgumentException     if a textual criterion exceeds 64 characters
     * @throws InvalidAuditLogRangeException if {@code from} is strictly after {@code to}
     */
    public AuditLogFilter {
        action = normalize(action, "action");
        entityType = normalize(entityType, "entityType");
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidAuditLogRangeException(from, to);
        }
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
        return trimmed;
    }
}
