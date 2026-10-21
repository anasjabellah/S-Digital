package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when the backend's own Keycloak integration is misconfigured or not
 * authorized (the service account is rejected or lacks the required permission).
 *
 * <p>This is deliberately distinct from {@link KeycloakUnavailableException}: a bad
 * service-account configuration is a server-side defect, not a transient
 * unavailability, so it must not be disguised as an "outage". It maps to HTTP 500
 * and never exposes any Keycloak detail or credential.</p>
 */
public class KeycloakIntegrationException extends RuntimeException {

    public KeycloakIntegrationException(String message) {
        super(message);
    }

    public KeycloakIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
