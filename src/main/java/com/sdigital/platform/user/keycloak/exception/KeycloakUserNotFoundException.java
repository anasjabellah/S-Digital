package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when the target Keycloak account cannot be found through the Admin API.
 *
 * <p>Mapped to HTTP 404. The local user is linked to a {@code keycloakId} that no
 * longer resolves in Keycloak; nothing is created or repaired automatically.</p>
 */
public class KeycloakUserNotFoundException extends RuntimeException {

    public KeycloakUserNotFoundException() {
        super("Keycloak user not found");
    }
}
