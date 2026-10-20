package com.sdigital.platform.user.dto;

import com.sdigital.platform.agency.entity.AgencyStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Minimal agency representation embedded in {@link UserResponse}.
 */
@Schema(name = "UserAgencyDto", description = "Minimal agency representation embedded in a user response.")
public record UserAgencyDto(
        @Schema(description = "Agency identifier.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,
        @Schema(description = "Agency code.", example = "AG-PARIS-01")
        String code,
        @Schema(description = "Agency name.", example = "Agency Paris 01")
        String name,
        @Schema(description = "Agency status.", example = "ACTIVE")
        AgencyStatus status
) {
}
