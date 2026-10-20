package com.sdigital.platform.agency.dto;

import com.sdigital.platform.agency.entity.AgencyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model returned to clients for an agency.
 */
@Schema(name = "AgencyResponse", description = "Agency representation returned by the API.")
public record AgencyResponse(

        @Schema(description = "Server-generated identifier.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Unique code of the agency.", example = "AG-PARIS-01")
        String code,

        @Schema(description = "Display name of the agency.", example = "Agency Paris 01")
        String name,

        @Schema(description = "Agency status. `ACTIVE` for a usable agency, `INACTIVE` after logical deactivation.",
                example = "ACTIVE")
        AgencyStatus status,

        @Schema(description = "Creation timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant createdAt,

        @Schema(description = "Last modification timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant updatedAt
) {
}
