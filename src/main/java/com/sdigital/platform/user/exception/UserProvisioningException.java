package com.sdigital.platform.user.exception;

/**
 * Raised when a provisioning request could not be completed after the remote Keycloak
 * identity was created, or when Keycloak returned an unusable identity id.
 *
 * <p>The message is deliberately generic: it never contains the email address, the
 * Keycloak user id, a token, a password or any upstream response body. The coarse
 * {@link Reason} is meant for server-side diagnostics only.</p>
 */
public class UserProvisioningException extends RuntimeException {

    /**
     * Coarse, non-sensitive failure classification.
     */
    public enum Reason {
        /** Keycloak answered 2xx but no internal user id could be resolved. */
        KEYCLOAK_USER_ID_MISSING,
        /** Keycloak returned an id that cannot be persisted as {@code keycloak_id}. */
        KEYCLOAK_USER_ID_INVALID,
        /** The local user/link could not be persisted after the remote identity was created. */
        LOCAL_PERSISTENCE_FAILED
    }

    private final Reason reason;

    public UserProvisioningException(Reason reason) {
        super("User provisioning failed");
        this.reason = reason;
    }

    public UserProvisioningException(Reason reason, Throwable cause) {
        super("User provisioning failed", cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
