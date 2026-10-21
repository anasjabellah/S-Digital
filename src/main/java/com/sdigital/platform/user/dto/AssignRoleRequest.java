package com.sdigital.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for assigning a single realm role to a user.
 *
 * <p>The role name is validated by Bean Validation for shape only; the business
 * allowlist check happens in the service.</p>
 */
@Schema(name = "AssignRoleRequest", description = "Assign one realm role to a user.")
public record AssignRoleRequest(

        @Schema(description = "Realm role name to assign.", example = "MANAGER")
        @NotBlank(message = "roleName must not be blank")
        @Size(max = 255, message = "roleName must not exceed 255 characters")
        String roleName
) {
}
