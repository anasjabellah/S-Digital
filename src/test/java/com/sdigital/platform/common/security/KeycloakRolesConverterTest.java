package com.sdigital.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KeycloakRolesConverter}.
 *
 * <p>No Spring context and no Keycloak server: {@link Jwt} instances are built directly with
 * representative Keycloak claim shapes (the same structure Keycloak serialises into the access
 * token). They cover the role-mapping contract: realm roles, trusted client roles, mixed tokens,
 * roleless tokens, missing/{@code null}/malformed claims and unknown claims.</p>
 */
class KeycloakRolesConverterTest {

    private static final String TRUSTED_CLIENT = "s-digital-api";

    private final KeycloakRolesConverter converter = new KeycloakRolesConverter(List.of(TRUSTED_CLIENT));

    // --- Scenario 1: no realm_access / resource_access at all --------------------------------

    @Test
    void tokenWithoutRealmAccessHasNoAuthorities() {
        assertThat(authorities(jwt(Map.of(
                "scope", "profile email",
                "preferred_username", "testuser"))))
                .isEmpty();
    }

    @Test
    void realmAccessWithEmptyRolesHasNoAuthorities() {
        assertThat(authorities(jwt(Map.of("realm_access", Map.of("roles", List.of()))))).isEmpty();
    }

    // --- Scenario 2: realm roles -------------------------------------------------------------

    @Test
    void realmRoleAdminIsMappedToRoleAdmin() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN"))));
        assertThat(authorities(jwt)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void realmRoleManagerIsMappedToRoleManager() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("MANAGER"))));
        assertThat(authorities(jwt)).containsExactly("ROLE_MANAGER");
    }

    @Test
    void realmRoleUserIsMappedToRoleUser() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("USER"))));
        assertThat(authorities(jwt)).containsExactly("ROLE_USER");
    }

    @Test
    void multipleBusinessRolesAreMapped() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "MANAGER", "USER"))));
        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_USER");
    }

    @Test
    void noBusinessRolesMeansNoImplicitAuthorities() {
        Jwt jwt = jwt(Map.of("scope", "profile email"));
        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void unknownRealmRoleDoesNotBecomeAuthority() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("UNKNOWN"))));
        assertThat(authorities(jwt)).containsExactly("ROLE_UNKNOWN");
    }

    @Test
    void mixedKnownAndUnknownRolesMapsOnlyKnown() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "UNKNOWN"))));
        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_UNKNOWN");
    }

    @Test
    void malformedRolesClaimYieldsNoAuthorities() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", "ADMIN")));
        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void multipleRealmRolesAreAllMapped() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "MANAGER", "USER"))));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_USER");
    }

    // --- Scenario 3: client roles for a trusted client ---------------------------------------

    @Test
    void clientRoleOfTrustedClientIsMappedWithClientNamespace() {
        Jwt jwt = jwt(Map.of("resource_access", Map.of(
                TRUSTED_CLIENT, Map.of("roles", List.of("viewer")))));

        assertThat(authorities(jwt)).containsExactly("CLIENT_s-digital-api_viewer");
    }

    // --- Scenario 4: realm and client roles combined -----------------------------------------

    @Test
    void realmAndClientRolesAreBothMapped() {
        Jwt jwt = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN")),
                "resource_access", Map.of(TRUSTED_CLIENT, Map.of("roles", List.of("editor", "viewer")))));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder(
                "ROLE_ADMIN",
                "CLIENT_s-digital-api_editor",
                "CLIENT_s-digital-api_viewer");
    }

    // --- Collision avoidance: same role name in realm and client stay distinct ---------------

    @Test
    void realmRoleAndClientRoleWithSameNameDoNotCollide() {
        Jwt jwt = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("admin")),
                "resource_access", Map.of(TRUSTED_CLIENT, Map.of("roles", List.of("admin")))));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_admin", "CLIENT_s-digital-api_admin");
    }

    // --- Scenario 5: missing / null / malformed claims ---------------------------------------

    @Test
    void ignoresRealmAccessThatIsNotAMap() {
        Jwt jwt = jwt(Map.of("realm_access", "not-a-map"));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void ignoresRolesClaimThatIsNotACollection() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", "ADMIN")));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void ignoresNullOrNonStringOrBlankRoleEntries() {
        // A list containing null, a number and a blank string must not grant anything and must
        // not throw; only the valid "USER" entry is kept.
        java.util.List<Object> mixed = java.util.Arrays.asList(null, 42, "  ", "USER");
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", mixed)));

        assertThat(authorities(jwt)).containsExactly("ROLE_USER");
    }

    @Test
    void ignoresResourceAccessThatIsNotAMap() {
        Jwt jwt = jwt(Map.of("resource_access", List.of("x")));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void ignoresClientEntryThatIsNotAMap() {
        Jwt jwt = jwt(Map.of("resource_access", Map.of(TRUSTED_CLIENT, "not-a-map")));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void nullJwtYieldsNoAuthorities() {
        assertThat(converter.convert(null)).isEmpty();
    }

    @Test
    void converterNeverReturnsNull() {
        assertThat(converter.convert(jwt(Map.of("preferred_username", "testuser")))).isNotNull().isEmpty();
    }

    // --- Trusted-client restriction: roles of unrelated clients are ignored ------------------

    @Test
    void clientRolesOfUntrustedClientsAreIgnored() {
        Jwt jwt = jwt(Map.of("resource_access", Map.of(
                "some-other-client", Map.of("roles", List.of("admin")),
                "account", Map.of("roles", List.of("manage-account")))));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void onlyTrustedClientRolesAreMappedWhenSeveralClientsArePresent() {
        Jwt jwt = jwt(Map.of("resource_access", Map.of(
                TRUSTED_CLIENT, Map.of("roles", List.of("viewer")),
                "some-other-client", Map.of("roles", List.of("admin")))));

        assertThat(authorities(jwt)).containsExactly("CLIENT_s-digital-api_viewer");
    }

    @Test
    void emptyTrustedClientListMapsNoClientRoles() {
        KeycloakRolesConverter realmOnly = new KeycloakRolesConverter(List.of());
        Jwt jwt = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN")),
                "resource_access", Map.of(TRUSTED_CLIENT, Map.of("roles", List.of("viewer")))));

        assertThat(authorities(realmOnly.convert(jwt))).containsExactly("ROLE_ADMIN");
    }

    // --- Scenario 8: no privilege elevation from unknown claims ------------------------------

    @Test
    void ignoresUnknownTopLevelRolesClaim() {
        Jwt jwt = jwt(Map.of("roles", List.of("ADMIN"), "authorities", List.of("ROLE_ADMIN")));

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void doesNotMapOAuth2Scopes() {
        Jwt jwt = jwt(Map.of("scope", "profile email", "scp", List.of("read", "write")));

        assertThat(authorities(jwt)).isEmpty();
    }

    // --- Role names are preserved verbatim (case) and trimmed --------------------------------

    @Test
    void roleNameCaseIsPreservedAndTrimmed() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("  Custom_Role  "))));

        assertThat(authorities(jwt)).containsExactly("ROLE_Custom_Role");
    }

    // --- Claims captured from a real token issued by the local realm -------------------------

    /**
     * The exact claim set of a real access token issued by the local {@code s-digital} realm for
     * {@code testuser} (no role assigned). Proves the real claim shape yields no authority and
     * does not throw.
     */
    @Test
    void realRolelessTokenFromLocalRealmYieldsNoAuthorities() {
        Jwt jwt = jwt(Map.of(
                "azp", "s-digital-api",
                "scope", "profile email",
                "preferred_username", "testuser",
                "email_verified", true,
                "name", "Test User",
                "given_name", "Test",
                "family_name", "User",
                "email", "testuser@example.com"));

        assertThat(authorities(jwt)).isEmpty();
    }

    /**
     * The exact realm claim of a real token issued by the local {@code s-digital} realm once the
     * built-in (non-business) realm roles {@code offline_access} and {@code uma_authorization} were
     * temporarily assigned to {@code testuser} (assignment reverted afterwards). Proves the real
     * Keycloak claim shape is converted as documented.
     */
    @Test
    void realTokenWithRealmRolesFromLocalRealmYieldsRealmAuthorities() {
        Jwt jwt = jwt(Map.of(
                "azp", "s-digital-api",
                "realm_access", Map.of("roles", List.of("offline_access", "uma_authorization")),
                "scope", "profile email",
                "preferred_username", "testuser"));

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_offline_access", "ROLE_uma_authorization");
    }

    private Set<String> authorities(Jwt jwt) {
        return authorities(converter.convert(jwt));
    }

    private static Set<String> authorities(Collection<GrantedAuthority> granted) {
        return granted.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
