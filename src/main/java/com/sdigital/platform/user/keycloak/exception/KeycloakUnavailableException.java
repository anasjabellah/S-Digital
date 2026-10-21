package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when the Keycloak Admin integration is unavailable or not configured:
 * network failure, timeout, 5xx from Keycloak, or missing configuration.
 *
 * <p>Mapped to HTTP 503. The original cause is kept for server-side logging only
 * and is never serialized into the HTTP response. Mutations are never retried
 * automatically.</p>
 */
public class KeycloakUnavailableException extends RuntimeException {

    public KeycloakUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public KeycloakUnavailableException(String message) {
        super(message);
    }
}
