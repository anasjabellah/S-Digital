package com.sdigital.platform.user.keycloak.exception;

/**
 * Raised when the Keycloak Admin API answers with an error status that carries a
 * specific meaning (authentication, authorization or rate limiting), so the service
 * can map it to a controlled HTTP response instead of an undifferentiated
 * "unavailable".
 *
 * <p>The Keycloak status code is kept for server-side classification only; it is
 * never serialized to the client and no Keycloak response body is attached.</p>
 */
public class KeycloakUpstreamException extends RuntimeException {

    /**
     * Classification of an upstream Keycloak failure.
     */
    public enum Kind {
        /** Keycloak rejected our credentials/token (401) — a backend configuration problem. */
        UNAUTHENTICATED,
        /** Keycloak refused the operation (403) — the service account lacks the permission. */
        FORBIDDEN,
        /** Keycloak rejected the request because the identity already exists (409). */
        CONFLICT,
        /** Keycloak throttled the request (429). */
        RATE_LIMITED,
        /** Keycloak returned a server error (5xx). */
        SERVER_ERROR
    }

    private final Kind kind;
    private final int upstreamStatus;

    public KeycloakUpstreamException(Kind kind, int upstreamStatus, String message) {
        super(message);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
    }

    public Kind getKind() {
        return kind;
    }

    public int getUpstreamStatus() {
        return upstreamStatus;
    }
}
