package com.sdigital.platform.audit.dto;

import com.sdigital.platform.audit.entity.AuditOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read model of a single audit event.
 *
 * <p>This is a dedicated projection, not the JPA entity: the entity exposes no mutable
 * state, but returning it would couple the API contract to the persistence model and
 * would risk leaking columns that are not part of the public contract.</p>
 *
 * <p>Only the audit metadata is exposed. No user object, no Keycloak identifier, no
 * token and no secret is ever part of this payload. The {@code details} map contains
 * the strictly filtered metadata that was selected at write time (statuses, changed
 * field names, role names, reason codes).</p>
 */
@Schema(name = "AuditLogResponse", description = "A single audit event returned by the API.")
public record AuditLogResponse(

        @Schema(description = "Server-generated identifier of the event.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Moment the event was recorded (UTC).", example = "2026-01-01T12:00:00Z")
        Instant occurredAt,

        @Schema(description = "Local identifier of the acting user. Null when the actor could not be "
                + "resolved (system or unauthenticated context).")
        UUID actorUserId,

        @Schema(description = "Stable action code of the audited operation.", example = "USER_UPDATED")
        String action,

        @Schema(description = "Type of the affected resource.", example = "USER")
        String entityType,

        @Schema(description = "Identifier of the affected resource. Null when the event has no target.")
        String entityId,

        @Schema(description = "Outcome of the audited operation.", example = "SUCCESS")
        AuditOutcome outcome,

        @Schema(description = "Structured metadata of the event. Never contains tokens, secrets, "
                + "authorization headers or full personal data.")
        Map<String, Object> details
) {
}
