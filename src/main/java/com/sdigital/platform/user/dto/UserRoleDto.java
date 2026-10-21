package com.sdigital.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Minimal representation of a Keycloak <em>realm</em> role.
 *
 * <p>Only the role name is exposed: Keycloak internal fields (id, containerId,
 * composite flags, attributes) are never leaked to clients.</p>
 */
@Schema(name = "UserRoleDto", description = "A Keycloak realm role assigned to a user.")
public record UserRoleDto(

        @Schema(description = "Realm role name.", example = "MANAGER")
        String name
) {
}
