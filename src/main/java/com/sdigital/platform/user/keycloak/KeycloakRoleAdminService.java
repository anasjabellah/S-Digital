package com.sdigital.platform.user.keycloak;

import com.sdigital.platform.common.security.KeycloakIdentityResolver;
import com.sdigital.platform.user.dto.UserRoleDto;
import com.sdigital.platform.user.dto.UserRolesResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.keycloak.exception.KeycloakIntegrationException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUserNotFoundException;
import com.sdigital.platform.user.keycloak.exception.RoleChangeDeniedException;
import com.sdigital.platform.user.keycloak.exception.RoleNotAllowedException;
import com.sdigital.platform.user.keycloak.exception.UserNotLinkedToKeycloakException;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business operations for consulting and managing a user's Keycloak <em>realm</em> roles.
 *
 * <h2>Provisional rules (isolated here for later review)</h2>
 * <ul>
 *     <li>Realm roles only; client roles are never touched.</li>
 *     <li>{@code ADMIN} can never be assigned or removed through the API.</li>
 *     <li>An administrator can never change their own roles (JWT {@code sub} equals the
 *         target's {@code keycloakId}).</li>
 *     <li>Only roles present in the configured allowlist are accepted.</li>
 *     <li>When the allowlist is missing/empty, or Keycloak is not configured, every
 *         operation fails closed.</li>
 *     <li>No email lookup and no auto-provisioning: a local user must already be linked
 *         through {@code keycloakId}.</li>
 * </ul>
 *
 * <p>These rules are expected to be revisited once the business decisions are
 * confirmed; they are centralized in {@link #ensureRoleIsManageable(String)} and the
 * allowlist/configuration guards so a later change stays local.</p>
 */
@Service
public class KeycloakRoleAdminService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakRoleAdminService.class);

    /** Reserved role that must never be granted or revoked through the API. */
    static final String RESERVED_ADMIN_ROLE = "ADMIN";

    static final String ACTION_ASSIGN = "ASSIGN";
    static final String ACTION_REMOVE = "REMOVE";

    static final String REASON_SELF_MODIFICATION = "SELF_MODIFICATION_DENIED";
    static final String REASON_RESERVED_ROLE = "RESERVED_ROLE_DENIED";
    static final String REASON_NOT_ALLOWLISTED = "NOT_ALLOWLISTED";
    static final String REASON_KEYCLOAK_DISABLED = "KEYCLOAK_ACCOUNT_DISABLED";

    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;
    private final RoleChangeAuditLogger auditLogger;

    public KeycloakRoleAdminService(UserRepository userRepository,
                                    KeycloakAdminClient keycloakAdminClient,
                                    KeycloakAdminProperties properties,
                                    RoleChangeAuditLogger auditLogger) {
        this.userRepository = userRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
        this.auditLogger = auditLogger;
    }

    /**
     * Lists the target user's realm roles, restricted to the allowlist. Roles outside the
     * allowlist (including {@code ADMIN}) are never returned.
     */
    @Transactional(readOnly = true)
    public UserRolesResponse getRoles(UUID targetUserId) {
        ensureIntegrationConfigured();
        TargetUser target = resolveTarget(targetUserId, false);
        List<String> roles = guardedKeycloakCall(() -> keycloakAdminClient.listRealmRoles(target.keycloakUserId()));
        Set<String> allowlist = properties.allowlistAsSet();
        List<UserRoleDto> visible = roles.stream()
                .filter(allowlist::contains)
                .map(UserRoleDto::new)
                .toList();
        return new UserRolesResponse(visible);
    }

    /**
     * Assigns a single realm role to the target user after applying every security rule.
     */
    @Transactional(readOnly = true)
    public UserRolesResponse assignRole(UUID targetUserId, String roleName) {
        String normalizedRole = normalizeRole(roleName);
        MutationContext context = prepareMutation(targetUserId, normalizedRole, ACTION_ASSIGN);

        boolean roleExists = guardedKeycloakCall(
                () -> keycloakAdminClient.addRealmRole(context.target().keycloakUserId(), normalizedRole));
        if (!roleExists) {
            throw new RoleNotAllowedException("Unknown role");
        }

        auditLogger.roleChanged(context.actorUserId(), context.actorKeycloakId(),
                context.target().localUserId(), context.target().keycloakId(), normalizedRole, ACTION_ASSIGN);
        return getRoles(targetUserId);
    }

    /**
     * Removes a single realm role from the target user after applying every security rule.
     */
    @Transactional(readOnly = true)
    public UserRolesResponse removeRole(UUID targetUserId, String roleName) {
        String normalizedRole = normalizeRole(roleName);
        MutationContext context = prepareMutation(targetUserId, normalizedRole, ACTION_REMOVE);

        boolean roleExists = guardedKeycloakCall(
                () -> keycloakAdminClient.removeRealmRole(context.target().keycloakUserId(), normalizedRole));
        if (!roleExists) {
            throw new RoleNotAllowedException("Unknown role");
        }

        auditLogger.roleChanged(context.actorUserId(), context.actorKeycloakId(),
                context.target().localUserId(), context.target().keycloakId(), normalizedRole, ACTION_REMOVE);
        return getRoles(targetUserId);
    }

    // --- rule enforcement ----------------------------------------------------------------------

    /**
     * Applies configuration guards, allowlist, reserved-role and self-modification rules.
     * Returns the mutation context (actor + resolved target) so the caller can perform the
     * Keycloak call and audit it.
     */
    private MutationContext prepareMutation(UUID targetUserId, String roleName, String action) {
        ensureIntegrationConfigured();
        ensureAllowlistConfigured();
        ensureRoleIsManageable(roleName);

        TargetUser target = resolveTarget(targetUserId, true);
        ActorContext actor = resolveActor();

        String actorKeycloakId = actor.keycloakId();
        if (StringUtils.hasText(actorKeycloakId)
                && actorKeycloakId.equals(target.keycloakId())) {
            auditLogger.roleChangeDenied(actor.localUserId(), target.localUserId(), roleName,
                    action, REASON_SELF_MODIFICATION);
            throw new RoleChangeDeniedException("Modifying your own roles is not allowed");
        }

        return new MutationContext(actor.localUserId(), actorKeycloakId, target);
    }

    private void ensureIntegrationConfigured() {
        if (!properties.isConfigured()) {
            throw new KeycloakUnavailableException("Keycloak admin integration is not configured");
        }
    }

    private void ensureAllowlistConfigured() {
        if (!properties.isAllowlistConfigured()) {
            throw new RoleChangeDeniedException("Role management is disabled: no manageable roles configured");
        }
    }

    /**
     * Provisional role policy, centralized for future review.
     */
    private void ensureRoleIsManageable(String roleName) {
        if (RESERVED_ADMIN_ROLE.equalsIgnoreCase(roleName)) {
            throw new RoleChangeDeniedException("The ADMIN role cannot be managed through the API");
        }
        if (!properties.allowlistAsSet().contains(roleName)) {
            throw new RoleChangeDeniedException("Role is not in the manageable allowlist");
        }
    }

    private TargetUser resolveTarget(UUID targetUserId, boolean requireEnabled) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException(targetUserId));

        String keycloakId = user.getKeycloakId();
        if (!StringUtils.hasText(keycloakId)) {
            throw new UserNotLinkedToKeycloakException(targetUserId);
        }
        String trimmed = keycloakId.trim();

        String keycloakUserId = guardedKeycloakCall(() -> keycloakAdminClient.findUserId(trimmed));
        if (keycloakUserId == null) {
            throw new KeycloakUserNotFoundException();
        }

        if (requireEnabled) {
            boolean enabled = guardedKeycloakCall(() -> keycloakAdminClient.isUserEnabled(keycloakUserId));
            if (!enabled) {
                throw new RoleChangeDeniedException("The Keycloak account is disabled");
            }
        }

        return new TargetUser(targetUserId, trimmed, keycloakUserId);
    }

    private ActorContext resolveActor() {
        String actorKeycloakId = KeycloakIdentityResolver.getCurrentKeycloakSub().orElse(null);
        UUID actorLocalUserId = null;
        if (StringUtils.hasText(actorKeycloakId)) {
            Optional<User> actor = userRepository.findByKeycloakId(actorKeycloakId);
            actorLocalUserId = actor.map(User::getId).orElse(null);
        }
        return new ActorContext(actorLocalUserId, actorKeycloakId);
    }

    private static String normalizeRole(String roleName) {
        if (!StringUtils.hasText(roleName)) {
            throw new RoleNotAllowedException("Role name must not be blank");
        }
        return roleName.trim();
    }

    /**
     * Wraps a Keycloak call, translating any runtime failure into a controlled
     * {@link KeycloakUnavailableException} so no internal detail or secret leaks upward.
     */
    private <T> T guardedKeycloakCall(KeycloakCall<T> call) {
        try {
            return call.execute();
        } catch (KeycloakUnavailableException ex) {
            throw ex;
        } catch (KeycloakUpstreamException ex) {
            throw classifyUpstreamFailure(ex);
        } catch (RuntimeException ex) {
            log.warn("Keycloak admin call failed: {}", ex.getClass().getSimpleName());
            throw new KeycloakUnavailableException("Keycloak is currently unavailable", ex);
        }
    }

    /**
     * Maps a classified upstream failure without ever pretending a backend
     * authorization/configuration problem is a mere network outage:
     * <ul>
     *     <li>401/403 (token rejected or missing permission) → integration defect (500);</li>
     *     <li>429 / 5xx → transient unavailability (503).</li>
     * </ul>
     * No Keycloak status code, body or credential is propagated to the caller.
     */
    private RuntimeException classifyUpstreamFailure(KeycloakUpstreamException ex) {
        KeycloakUpstreamException.Kind kind = ex.getKind();
        if (kind == KeycloakUpstreamException.Kind.UNAUTHENTICATED
                || kind == KeycloakUpstreamException.Kind.FORBIDDEN) {
            log.error("Keycloak admin integration rejected our service account ({}): {}",
                    kind, ex.getUpstreamStatus());
            return new KeycloakIntegrationException("Keycloak admin integration is not authorized");
        }
        log.warn("Keycloak admin upstream failure ({}): {}", kind, ex.getUpstreamStatus());
        return new KeycloakUnavailableException("Keycloak is currently unavailable", ex);
    }

    @FunctionalInterface
    private interface KeycloakCall<T> {
        T execute();
    }

    private record TargetUser(UUID localUserId, String keycloakId, String keycloakUserId) {
    }

    private record ActorContext(UUID localUserId, String keycloakId) {
    }

    private record MutationContext(UUID actorUserId, String actorKeycloakId, TargetUser target) {
    }
}
