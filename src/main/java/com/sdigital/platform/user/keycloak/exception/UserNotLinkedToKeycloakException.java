package com.sdigital.platform.user.keycloak.exception;

import java.util.UUID;

/**
 * Raised when a local user exists but is not linked to a Keycloak account
 * ({@code keycloakId} is null or blank).
 *
 * <p>Mapped to HTTP 422: the request is understood but cannot be processed until
 * the account is linked. No email-based lookup and no auto-provisioning are
 * performed to "fix" the situation.</p>
 */
public class UserNotLinkedToKeycloakException extends RuntimeException {

    public UserNotLinkedToKeycloakException(UUID userId) {
        super("Local user " + userId + " is not linked to a Keycloak account");
    }
}
