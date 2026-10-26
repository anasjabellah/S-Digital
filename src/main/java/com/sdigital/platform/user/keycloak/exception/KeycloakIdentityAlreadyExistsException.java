package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when Keycloak already owns an identity with the requested username or email.
 *
 * <p>This is a business conflict (HTTP 409), not an outage or a configuration defect.
 * The message is generic and never echoes the email address or a Keycloak response
 * body.</p>
 */
public class KeycloakIdentityAlreadyExistsException extends RuntimeException {

    public KeycloakIdentityAlreadyExistsException() {
        super("A Keycloak account already exists for this email");
    }
}
