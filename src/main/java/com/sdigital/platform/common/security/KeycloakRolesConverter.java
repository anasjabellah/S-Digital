package com.sdigital.platform.common.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps the Keycloak <em>role</em> claims of a validated JWT to Spring Security authorities.
 *
 * <p>Two kinds of Keycloak roles are read, and only those two:
 * <ul>
 *     <li><b>Realm roles</b> from {@code realm_access.roles}:</li>
 *     <li><b>Client roles</b> from {@code resource_access.<client-id>.roles}, but only for the
 *     client ids explicitly listed as trusted by configuration.</li>
 * </ul>
 *
 * <h2>Authority naming convention</h2>
 * <table border="1">
 *     <caption>Claims to authorities</caption>
 *     <tr><th>Source</th><th>Example claim</th><th>Authority</th></tr>
 *     <tr><td>Realm role</td><td>{@code realm_access.roles = ["ADMIN"]}</td><td>{@code ROLE_ADMIN}</td></tr>
 *     <tr><td>Client role</td><td>{@code resource_access.s-digital-api.roles = ["viewer"]}</td>
 *         <td>{@code CLIENT_s-digital-api_viewer}</td></tr>
 * </table>
 *
 * <p>Realm roles keep the Spring Security {@code ROLE_} prefix so they can be used with
 * {@code hasRole("ADMIN")}; client roles use a separate {@code CLIENT_<clientId>_} namespace so
 * the two sources can never collide, no matter what role names Keycloak contains. Role names
 * are used verbatim (case is preserved).</p>
 *
 * <h2>Deliberately NOT mapped</h2>
 * <ul>
 *     <li><b>OAuth2 scopes</b> ({@code scope}/{@code scp}): scopes are grant/permission requests,
 *     not roles. They are intentionally not turned into authorities so roles and scopes are never
 *     confused.</li>
 *     <li><b>Any other / unknown claim</b> (for example a top-level {@code roles} array): ignoring
 *     unknown claims prevents privilege elevation from claims that are not part of Keycloak's
 *     role model.</li>
 *     <li><b>Client roles of non-trusted clients</b>: even if present in {@code resource_access},
 *     only the configured client ids contribute authorities.</li>
 * </ul>
 *
 * <p>A valid token that carries no role therefore yields an empty authority collection: the
 * request stays <em>authenticated</em> but receives no implicit business authority. Missing,
 * {@code null} or malformed claims are ignored safely (never throwing, never granting extra
 * privileges).</p>
 */
public class KeycloakRolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Prefix applied to realm roles (Spring Security's {@code ROLE_} convention). */
    public static final String REALM_ROLE_PREFIX = "ROLE_";

    /** Prefix applied to client roles, producing {@code CLIENT_<clientId>_<role>}. */
    public static final String CLIENT_ROLE_PREFIX = "CLIENT_";

    private static final String CLIENT_ROLE_SEPARATOR = "_";

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_CLAIM = "roles";

    private final Set<String> trustedClientIds;

    /**
     * @param trustedClientIds client ids whose {@code resource_access.<client-id>.roles} entries
     *                         are trusted. Blank entries are ignored; an empty collection means
     *                         "no client roles are mapped" (realm roles only).
     */
    public KeycloakRolesConverter(Collection<String> trustedClientIds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (trustedClientIds != null) {
            for (String clientId : trustedClientIds) {
                if (StringUtils.hasText(clientId)) {
                    normalized.add(clientId.trim());
                }
            }
        }
        this.trustedClientIds = Set.copyOf(normalized);
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (jwt == null) {
            return List.of();
        }
        addRealmRoles(jwt, authorities);
        addClientRoles(jwt, authorities);
        return List.copyOf(authorities);
    }

    private void addRealmRoles(Jwt jwt, Set<GrantedAuthority> authorities) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (realmAccess instanceof Map<?, ?> realmAccessMap) {
            for (String role : extractRoleNames(realmAccessMap.get(ROLES_CLAIM))) {
                authorities.add(new SimpleGrantedAuthority(REALM_ROLE_PREFIX + role));
            }
        }
    }

    private void addClientRoles(Jwt jwt, Set<GrantedAuthority> authorities) {
        Object resourceAccess = jwt.getClaim(RESOURCE_ACCESS_CLAIM);
        if (!(resourceAccess instanceof Map<?, ?> resourceAccessMap)) {
            return;
        }
        for (String clientId : trustedClientIds) {
            Object clientAccess = resourceAccessMap.get(clientId);
            if (clientAccess instanceof Map<?, ?> clientAccessMap) {
                for (String role : extractRoleNames(clientAccessMap.get(ROLES_CLAIM))) {
                    authorities.add(new SimpleGrantedAuthority(
                            CLIENT_ROLE_PREFIX + clientId + CLIENT_ROLE_SEPARATOR + role));
                }
            }
        }
    }

    /**
     * Extracts a list of non-blank role names from a {@code roles} claim value, safely ignoring
     * anything that does not match the expected shape (not a collection, non-string or blank
     * entries).
     */
    private static List<String> extractRoleNames(Object rolesClaim) {
        if (!(rolesClaim instanceof Collection<?> rolesCollection)) {
            return List.of();
        }
        List<String> roleNames = new ArrayList<>();
        for (Object element : rolesCollection) {
            if (element instanceof String roleName && StringUtils.hasText(roleName)) {
                roleNames.add(roleName.trim());
            }
        }
        return roleNames;
    }
}
