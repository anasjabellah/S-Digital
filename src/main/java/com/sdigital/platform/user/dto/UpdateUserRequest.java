package com.sdigital.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request payload to update a user.
 *
 * <p>Contains only the fields considered modifiable by the team. Technical fields
 * ({@code id}, {@code createdAt}, {@code updatedAt}, {@code keycloakId}) and
 * {@code status} are intentionally absent: {@code status} is changed through the
 * dedicated deactivation operation, not through a generic update.</p>
 */
@Schema(name = "UpdateUserRequest", description = "Payload to update an existing user (full replacement of mutable fields).")
public record UpdateUserRequest(

        @Schema(description = "Unique email address of the user.", example = "ada.lovelace@example.com")
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        @Size(max = 320, message = "email must not exceed 320 characters")
        String email,

        @Schema(description = "First name of the user.", example = "Ada")
        @NotBlank(message = "firstName is required")
        @Size(max = 100, message = "firstName must not exceed 100 characters")
        String firstName,

        @Schema(description = "Last name of the user.", example = "Lovelace")
        @NotBlank(message = "lastName is required")
        @Size(max = 100, message = "lastName must not exceed 100 characters")
        String lastName,

        @Schema(description = "Identifier of the agency assigned to the user (must reference an ACTIVE agency).", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID agencyId,

        @Schema(description = "Identifier of the function assigned to the user (must reference an ACTIVE function).", example = "5fa85f64-5717-4562-b3fc-2c963f66afa7")
        UUID functionId
) {
}
