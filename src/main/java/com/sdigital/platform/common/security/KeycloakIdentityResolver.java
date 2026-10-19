package com.sdigital.platform.common.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Extracts the current Keycloak subject ({@code sub}) from the authenticated JWT in the
 * Spring Security context.
 *
 * <p>The {@code sub} claim is the stable, immutable identifier of the Keycloak account;
 * this resolver deliberately <em>does not</em> use any other claim (notably email) to avoid
 * account-linking based on mutable or potentially spoofable attributes.</p>
 *
 * <p>Behaviour:
 * <ul>
 *     <li>Returns {@link Optional#empty()} if there is no authentication in the context.</li>
 *     <li>Returns {@link Optional#empty()} if the authentication is anonymous
 *         ({@link AnonymousAuthenticationToken}).</li>
 *     <li>Returns {@link Optional#empty()} if the authentication is not a
 *         {@link JwtAuthenticationToken} (e.g. basic auth, test context without JWT).</li>
 *     <li>Returns {@link Optional#empty()} if the {@code sub} claim is missing, null, blank or
 *         only whitespace.</li>
 *     <li>Otherwise returns {@link Optional} containing the trimmed {@code sub}.</li>
 * </ul>
 *
 * <p>Exceptions thrown by this class never leak the JWT or sensitive data: only well-defined
 * exceptions from the caller (see {@link #getCurrentKeycloakSubOrThrow()}) may carry a generic
 * message.</p>
 */
public class KeycloakIdentityResolver {

    private KeycloakIdentityResolver() {
        // Utility class; no instances.
    }

    /**
     * Resolves the Keycloak subject ({@code sub}) of the currently authenticated principal, if
     * present and valid.
     *
     * @return an optional containing the trimmed {@code sub}, or empty if the identity cannot
     *         be determined safely.
     */
    public static Optional<String> getCurrentKeycloakSub() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtToken)) {
            return Optional.empty();
        }
        Object sub = jwtToken.getToken().getClaim("sub");
        if (!(sub instanceof String subValue)) {
            return Optional.empty();
        }
        String trimmed = subValue.trim();
        if (!StringUtils.hasText(trimmed)) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /**
     * Resolves the Keycloak subject ({@code sub}) of the currently authenticated principal or
     * throws if the identity cannot be determined.
     *
     * <p>The exception type and message are generic and do not include the token or any
     * sensitive claim values.</p>
     *
     * @return the trimmed {@code sub}
     * @throws IllegalStateException if no valid authenticated Keycloak identity is present
     */
    public static String getCurrentKeycloakSubOrThrow() {
        return getCurrentKeycloakSub()
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated Keycloak identity with a valid 'sub' claim is required."));
    }
}
