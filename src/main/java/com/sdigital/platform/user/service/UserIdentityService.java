package com.sdigital.platform.user.service;

import com.sdigital.platform.common.security.KeycloakIdentityResolver;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Read-only service that resolves the local {@link User} associated with the currently
 * authenticated Keycloak identity.
 *
 * <p>Identity resolution follows a strictly conservative policy:
 * <ul>
 *     <li>Only the Keycloak {@code sub} claim is used to establish the link
 *         ({@code user.keycloakId == sub}). The email is never used for automatic linking.</li>
 *     <li>No automatic provisioning is performed: if no local user is linked to the {@code sub},
 *         the resolution returns {@link Optional#empty()} (no user is created).</li>
 *     <li>Inactive linked users are returned as-is (the caller may decide to deny access);
 *         being linked does not imply implicit authorization.</li>
 *     <li>The caller is responsible for enforcing authorization rules (e.g. RBAC/permissions).
 *         This service does not grant access to other users' data.</li>
 * </ul>
 *
 * <p>All operations are read-only and transactional read-only where appropriate.</p>
 */
@Service
public class UserIdentityService {

    private final UserRepository userRepository;

    public UserIdentityService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Finds the local {@link User} whose {@code keycloakId} equals the provided Keycloak
     * subject ({@code sub}).
     *
     * <p>This method never creates a user and does not inspect any other claim.</p>
     *
     * @param keycloakSub the trimmed Keycloak {@code sub} claim; if {@code null} or blank,
     *                    {@link Optional#empty()} is returned.
     * @return an optional containing the matching local user, or empty if none exists.
     */
    @Transactional(readOnly = true)
    public Optional<User> findLocalUserByKeycloakSub(String keycloakSub) {
        if (keycloakSub == null) {
            return Optional.empty();
        }
        String trimmed = keycloakSub.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        return userRepository.findByKeycloakId(trimmed);
    }

    /**
     * Resolves the local {@link User} linked to the currently authenticated Keycloak identity.
     *
     * @return optional containing the linked local user, or empty if the current identity has
     *         no valid {@code sub} or no corresponding local user is linked.
     */
    @Transactional(readOnly = true)
    public Optional<User> resolveCurrentLocalUser() {
        return KeycloakIdentityResolver.getCurrentKeycloakSub()
                .flatMap(this::findLocalUserByKeycloakSub);
    }

    /**
     * Returns {@code true} if the currently authenticated Keycloak identity is linked to some
     * local {@link User} (regardless of its {@link UserStatus}).
     */
    @Transactional(readOnly = true)
    public boolean isCurrentUserLinked() {
        return resolveCurrentLocalUser().isPresent();
    }

    /**
     * Returns {@code true} if the currently authenticated Keycloak identity is linked to a
     * local {@link User} whose status is {@link UserStatus#ACTIVE}.
     */
    @Transactional(readOnly = true)
    public boolean isCurrentUserActiveLinked() {
        return resolveCurrentLocalUser()
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .isPresent();
    }

    /**
     * Returns the current Keycloak {@code sub} claim, or throws if no valid authenticated
     * identity is present.
     *
     * <p>The exception message is generic and does not include sensitive data.</p>
     *
     * @return the trimmed {@code sub}
     * @throws IllegalStateException if the current identity cannot be determined safely
     */
    public String getCurrentKeycloakSubOrThrow() {
        return KeycloakIdentityResolver.getCurrentKeycloakSubOrThrow();
    }
}
