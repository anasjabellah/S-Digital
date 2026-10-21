package com.sdigital.platform.user.keycloak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Minimal audit trail for successful role mutations, written to a dedicated logger.
 *
 * <p>This is intentionally additive and simple: it records a structured line per
 * successful change so the event is traceable, without claiming to be an immutable
 * audit log. Tokens, client secrets and raw HTTP bodies are never recorded; only
 * identifiers and role names are.</p>
 *
 * <p>Events are emitted on the {@code SDIGITAL_AUDIT} logger so they can be routed
 * independently of application logs.</p>
 */
@Component
public class RoleChangeAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("SDIGITAL_AUDIT");

    /**
     * Records a successful realm-role change.
     *
     * @param actorUserId        local id of the acting administrator (may be null)
     * @param actorKeycloakId    Keycloak id ({@code sub}) of the acting administrator
     * @param targetUserId       local id of the modified user
     * @param targetKeycloakId   Keycloak id of the modified user
     * @param roleName           realm role that changed
     * @param action             {@code ASSIGN} or {@code REMOVE}
     */
    public void roleChanged(UUID actorUserId,
                            String actorKeycloakId,
                            UUID targetUserId,
                            String targetKeycloakId,
                            String roleName,
                            String action) {
        AUDIT.info("event=ROLE_CHANGE action={} role={} actorUserId={} actorKeycloakId={} "
                        + "targetUserId={} targetKeycloakId={} outcome=SUCCESS",
                action, roleName, actorUserId, actorKeycloakId, targetUserId, targetKeycloakId);
    }

    /**
     * Records a denied or failed role-change attempt, for traceability of refused
     * operations. The reason is a coarse, non-sensitive code.
     */
    public void roleChangeDenied(UUID actorUserId,
                                 UUID targetUserId,
                                 String roleName,
                                 String action,
                                 String reasonCode) {
        AUDIT.info("event=ROLE_CHANGE action={} role={} actorUserId={} targetUserId={} "
                        + "outcome=DENIED reason={}",
                action, roleName, actorUserId, targetUserId, reasonCode);
    }
}
