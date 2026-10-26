package com.sdigital.platform.user.service;

import com.sdigital.platform.common.exception.DatabaseConstraints;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserAgencyNotFoundException;
import com.sdigital.platform.user.exception.UserFunctionNotFoundException;
import com.sdigital.platform.user.exception.UserProvisioningException;
import com.sdigital.platform.user.keycloak.KeycloakAdminClient;
import com.sdigital.platform.user.keycloak.KeycloakAdminProperties;
import com.sdigital.platform.user.keycloak.exception.KeycloakIdentityAlreadyExistsException;
import com.sdigital.platform.user.keycloak.exception.KeycloakIntegrationException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import com.sdigital.platform.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Coordinates the non-atomic provisioning of a local user and its Keycloak identity.
 *
 * <h2>Order of operations</h2>
 * <ol>
 *     <li>Normalize the request and reject a duplicate local email <em>before</em> touching
 *         Keycloak.</li>
 *     <li>Fail closed when the Keycloak Admin integration is not configured.</li>
 *     <li>Reject an existing Keycloak username/email, then create an enabled Keycloak
 *         identity with no credential, no required action and no role.</li>
 *     <li>Read the internal Keycloak user id from the creation response (or the exact
 *         username lookup fallback).</li>
 *     <li>Persist the local user and the {@code keycloak_id} in one local transaction.</li>
 * </ol>
 *
 * <h2>Failure handling</h2>
 * <p>If local persistence fails after the remote identity was created, the just-created
 * Keycloak identity is deleted (best effort) using only the id returned by the creation
 * call of this request. An existing Keycloak identity is never deleted, overwritten or
 * linked. If compensation itself fails, no sensitive data is logged: only the internal
 * Keycloak id and the exception class name are recorded for later reconciliation.</p>
 *
 * <p>The remaining failure mode cannot be eliminated without an outbox/reconciliation
 * workflow: a crash after Keycloak creation but before the local commit may leave an
 * unlinked Keycloak identity behind. Such an identity is not silently linked to another
 * account; a later provisioning attempt for the same email answers 409.</p>
 */
@Service
public class UserProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final KeycloakAdminClient keycloakAdminClient;
    private final KeycloakAdminProperties properties;

    public UserProvisioningService(UserRepository userRepository,
                                   UserService userService,
                                   KeycloakAdminClient keycloakAdminClient,
                                   KeycloakAdminProperties properties) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.keycloakAdminClient = keycloakAdminClient;
        this.properties = properties;
    }

    /**
     * Creates the local user and its Keycloak identity, then links them through the
     * Keycloak internal user id.
     *
     * <p>No credential is created and no realm or client role is assigned. The Keycloak
     * account is enabled; an administrator must set a password out of band before the user
     * can sign in.</p>
     */
    public UserResponse provision(CreateUserRequest request) {
        CreateUserRequest normalized = normalize(request);
        ensureLocalEmailAvailable(normalized.email());
        // Reject missing/inactive assignment references before creating the remote identity.
        userService.validateAssignments(normalized.agencyId(), normalized.functionId());
        ensureIntegrationConfigured();

        String keycloakUserId = createKeycloakIdentity(normalized);
        if (!StringUtils.hasText(keycloakUserId)) {
            throw new UserProvisioningException(UserProvisioningException.Reason.KEYCLOAK_USER_ID_MISSING);
        }
        String linkedId = keycloakUserId.trim();
        if (linkedId.length() > 255) {
            // The id cannot be persisted in app_user.keycloak_id. Ownership is certain
            // (it is the id returned by this request's creation call), so compensate.
            compensate(linkedId);
            throw new UserProvisioningException(UserProvisioningException.Reason.KEYCLOAK_USER_ID_INVALID);
        }

        return persistLocalLink(normalized, linkedId);
    }

    private String createKeycloakIdentity(CreateUserRequest request) {
        try {
            String existing = keycloakAdminClient.findUserIdByUsername(request.email());
            if (existing != null) {
                throw new KeycloakIdentityAlreadyExistsException();
            }
            return keycloakAdminClient.createUser(
                    request.email(),
                    request.email(),
                    request.firstName(),
                    request.lastName(),
                    true);
        } catch (KeycloakIdentityAlreadyExistsException ex) {
            throw ex;
        } catch (KeycloakUpstreamException ex) {
            if (ex.getKind() == KeycloakUpstreamException.Kind.CONFLICT) {
                throw new KeycloakIdentityAlreadyExistsException();
            }
            throw classify(ex);
        } catch (KeycloakIntegrationException | KeycloakUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Keycloak user creation failed: {}", ex.getClass().getSimpleName());
            throw new KeycloakUnavailableException("Keycloak is currently unavailable", ex);
        }
    }

    private UserResponse persistLocalLink(CreateUserRequest request, String keycloakUserId) {
        try {
            return userService.createProvisioned(request, keycloakUserId);
        } catch (EmailAlreadyUsedException | UserAgencyNotFoundException | UserFunctionNotFoundException ex) {
            // A deterministic local rejection: the remote identity must not survive.
            compensate(keycloakUserId);
            throw ex;
        } catch (RuntimeException ex) {
            compensate(keycloakUserId);
            if (isEmailNowUsed(request.email())) {
                // A concurrent request won the local email race after our pre-check.
                throw new EmailAlreadyUsedException(request.email());
            }
            if (ex instanceof DataIntegrityViolationException integrity
                    && DatabaseConstraints.isUniqueViolation(integrity)) {
                // A uniqueness race on the local link (typically the keycloak_id constraint)
                // is a business conflict, not a server failure: let the HTTP layer answer 409.
                throw integrity;
            }
            throw new UserProvisioningException(UserProvisioningException.Reason.LOCAL_PERSISTENCE_FAILED, ex);
        }
    }

    /**
     * Deletes only the identity created by the current request. Ownership is certain because
     * the id comes from the creation call of this same request; no id supplied by a client
     * or read from another account is ever used here.
     */
    private void compensate(String keycloakUserId) {
        try {
            keycloakAdminClient.deleteUser(keycloakUserId);
            // The identity is gone: no identifier or personal data needs to be retained.
            log.info("event=USER_PROVISIONING_COMPENSATED");
        } catch (RuntimeException ex) {
            // Do not log the cause message: it may contain upstream details. The internal
            // id and the exception type are enough to reconcile the orphan manually.
            log.error("event=USER_PROVISIONING_COMPENSATION_FAILED keycloakUserId={} cause={}",
                    keycloakUserId, ex.getClass().getSimpleName());
        }
    }

    private boolean isEmailNowUsed(String email) {
        try {
            return userRepository.existsByEmail(email);
        } catch (RuntimeException ex) {
            log.warn("Could not re-check local email uniqueness: {}", ex.getClass().getSimpleName());
            return false;
        }
    }

    private void ensureLocalEmailAvailable(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException(email);
        }
    }

    private void ensureIntegrationConfigured() {
        if (!properties.isConfigured()) {
            throw new KeycloakUnavailableException("Keycloak admin integration is not configured");
        }
    }

    /**
     * Maps a classified upstream failure without ever exposing the Keycloak status, body or
     * credential in the client response.
     */
    private RuntimeException classify(KeycloakUpstreamException ex) {
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

    private static CreateUserRequest normalize(CreateUserRequest request) {
        return new CreateUserRequest(
                request.email().trim().toLowerCase(Locale.ROOT),
                request.firstName(),
                request.lastName(),
                request.agencyId(),
                request.functionId());
    }
}
