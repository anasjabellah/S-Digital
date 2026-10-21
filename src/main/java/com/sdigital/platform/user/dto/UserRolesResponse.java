package com.sdigital.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Realm roles currently assigned to a user, restricted to the configured allowlist.
 *
 * <p>Roles outside the allowlist (in particular {@code ADMIN}) are never exposed,
 * so the response is scoped to the roles the API is allowed to manage.</p>
 */
@Schema(name = "UserRolesResponse", description = "Realm roles of a user, limited to the manageable allowlist.")
public record UserRolesResponse(

        @Schema(description = "Realm role names assigned to the user and present in the allowlist.")
        List<UserRoleDto> roles
) {
}
