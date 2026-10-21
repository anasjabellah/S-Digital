package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when the supplied role name is not valid for the operation (unknown, or
 * structurally invalid).
 *
 * <p>Mapped to HTTP 400 to stay consistent with the existing validation
 * conventions of the API.</p>
 */
public class RoleNotAllowedException extends RuntimeException {

    public RoleNotAllowedException(String message) {
        super(message);
    }
}
