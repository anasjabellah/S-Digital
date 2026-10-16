package com.sdigital.platform.user.dto;

import com.sdigital.platform.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model returned to clients.
 *
 * <p>{@code keycloakId} is deliberately not exposed at this stage (Keycloak is not
 * integrated yet).</p>
 */
@Schema(name = "UserResponse", description = "User representation returned by the API.")
public record UserResponse(

        @Schema(description = "Server-generated identifier.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Unique email address.", example = "ada.lovelace@example.com")
        String email,

        @Schema(description = "First name.", example = "Ada")
        String firstName,

        @Schema(description = "Last name.", example = "Lovelace")
        String lastName,

        @Schema(description = "User status. `ACTIVE` for a usable account, `INACTIVE` after logical deactivation.",
                example = "ACTIVE")
        UserStatus status,

        @Schema(description = "Creation timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant createdAt,

        @Schema(description = "Last modification timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant updatedAt
) {
}
