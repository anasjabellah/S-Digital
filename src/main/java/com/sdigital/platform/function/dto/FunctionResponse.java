package com.sdigital.platform.function.dto;

import com.sdigital.platform.function.entity.FunctionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model returned to clients for a function.
 */
@Schema(name = "FunctionResponse", description = "Function representation returned by the API.")
public record FunctionResponse(

        @Schema(description = "Server-generated identifier.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Unique code of the function.", example = "F-INGE")
        String code,

        @Schema(description = "Display name of the function.", example = "Engineer")
        String name,

        @Schema(description = "Function status. `ACTIVE` for a usable function, `INACTIVE` after logical deactivation.",
                example = "ACTIVE")
        FunctionStatus status,

        @Schema(description = "Creation timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant createdAt,

        @Schema(description = "Last modification timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant updatedAt
) {
}
