package com.sdigital.platform.agency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to create a new agency.
 *
 * <p>Technical fields ({@code id}, {@code status}, timestamps) are intentionally absent:
 * they are owned by the platform.</p>
 */
@Schema(name = "CreateAgencyRequest", description = "Payload to create a new agency.")
public record CreateAgencyRequest(

        @Schema(description = "Unique code of the agency.", example = "AG-PARIS-01")
        @NotBlank(message = "code is required")
        @Size(max = 50, message = "code must not exceed 50 characters")
        String code,

        @Schema(description = "Display name of the agency.", example = "Agency Paris 01")
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must not exceed 150 characters")
        String name
) {
}
