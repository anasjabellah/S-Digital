package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when a role cannot be assigned or removed because it is not allowed by
 * business rules: it is outside the allowlist, or it is the reserved {@code ADMIN}
 * role, or the caller is trying to modify their own roles.
 *
 * <p>Mapped to HTTP 403. The message stays generic so no policy detail is leaked.</p>
 */
public class RoleChangeDeniedException extends RuntimeException {

    public RoleChangeDeniedException(String message) {
        super(message);
    }
}
