package com.sdigital.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for a user updating their own profile.
 *
 * <p>Only the display names are self-editable. The email address, status, agency,
 * function and Keycloak link remain administration concerns and are never modified by
 * this endpoint.</p>
 */
@Schema(name = "UpdateCurrentUserRequest", description = "Payload to update the authenticated user's own profile.")
public record UpdateCurrentUserRequest(

        @Schema(description = "First name of the current user.", example = "Ada")
        @NotBlank(message = "firstName is required")
        @Size(max = 100, message = "firstName must not exceed 100 characters")
        String firstName,

        @Schema(description = "Last name of the current user.", example = "Lovelace")
        @NotBlank(message = "lastName is required")
        @Size(max = 100, message = "lastName must not exceed 100 characters")
        String lastName
) {
}
