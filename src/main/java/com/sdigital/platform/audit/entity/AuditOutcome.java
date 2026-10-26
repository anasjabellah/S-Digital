package com.sdigital.platform.audit.entity;

/**
 * Outcome of an audited operation.
 *
 * <p>The set is deliberately small and stable: it is stored as a string in the
 * {@code audit_log.outcome} column and constrained by a SQL {@code CHECK}. Adding
 * a new value requires a migration.</p>
 */
public enum AuditOutcome {

    /** The operation completed and changed state. */
    SUCCESS,

    /** The operation was refused by a business or security rule; no state changed. */
    DENIED,

    /** The operation failed (for example an integration or infrastructure error). */
    FAILURE,

    /** The operation was accepted but resulted in no change (idempotent no-op). */
    NO_CHANGE
}
