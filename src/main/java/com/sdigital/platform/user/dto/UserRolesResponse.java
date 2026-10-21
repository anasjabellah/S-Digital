package com.sdigital.platform.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Realm roles currently assigned to a user, restricted to the configured allowlist.
 *
 * <p>Roles outside the allowlist (in particular {@code ADMIN}) are never exposed by
 * name, so the response is scoped to the roles the API is allowed to manage. To still
 * let a client distinguish the meaningful states without leaking the excluded role
 * names, the additive {@link #hasUnmanageableRoles()} flag indicates whether the user
 * holds at least one realm role that this API may not manage:</p>
 * <ul>
 *     <li>{@code roles} empty and {@code hasUnmanageableRoles=false}: the user has no
 *         realm role at all;</li>
 *     <li>{@code roles} non-empty: the user holds at least one manageable role;</li>
 *     <li>{@code hasUnmanageableRoles=true}: the user additionally holds one or more
 *         realm roles outside the allowlist (their names are never disclosed).</li>
 * </ul>
 *
 * <p>The {@code roles} field keeps its original shape, so this addition is backward
 * compatible for existing clients.</p>
 */
@Schema(name = "UserRolesResponse", description = "Realm roles of a user, limited to the manageable allowlist.")
public record UserRolesResponse(

        @Schema(description = "Realm role names assigned to the user and present in the allowlist.")
        List<UserRoleDto> roles,

        @Schema(description = "True when the user holds at least one realm role outside the manageable "
                + "allowlist (role names are never disclosed). False otherwise.",
                example = "false")
        boolean hasUnmanageableRoles
) {

    /**
     * Convenience factory for the common case with no unmanageable roles.
     */
    public UserRolesResponse(List<UserRoleDto> roles) {
        this(roles, false);
    }
}
