package com.sdigital.platform.common.security;

import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the local id of the actor performing the current request.
 *
 * <p>The association follows the single rule used across the application: the authenticated
 * Keycloak {@code sub} claim must match {@code app_user.keycloak_id}. The email is never
 * used for linking and no local user is ever created implicitly.</p>
 *
 * <p>When no local user is linked to the current identity, {@code null} is returned instead
 * of a fabricated identifier: callers that record an audit event must be able to state that
 * the actor could not be resolved.</p>
 *
 * <p>This component performs a plain repository lookup. It declares no transaction of its
 * own, so it always joins the caller's transaction and never widens it.</p>
 */
@Component
public class CurrentActorResolver {

    private final UserRepository userRepository;

    public CurrentActorResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the local id of the acting user, or {@code null} when the current identity is
     * absent, anonymous or not linked to a local user.
     */
    public UUID resolveCurrentActorUserId() {
        return KeycloakIdentityResolver.getCurrentKeycloakSub()
                .flatMap(userRepository::findByKeycloakId)
                .map(User::getId)
                .orElse(null);
    }
}
