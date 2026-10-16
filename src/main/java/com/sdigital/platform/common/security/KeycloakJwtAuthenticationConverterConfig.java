package com.sdigital.platform.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Arrays;
import java.util.List;

/**
 * Wires Keycloak role mapping into the OAuth2 resource server.
 *
 * <p>Spring Security's default converter derives authorities from the OAuth2 <em>scopes</em>
 * ({@code scope}/{@code scp} claims, prefixed with {@code SCOPE_}) and knows nothing about
 * Keycloak roles. The {@link JwtAuthenticationConverter} built here replaces that behaviour with
 * {@link KeycloakRolesConverter}, which maps Keycloak <em>roles</em> only (see that class for the
 * naming convention and the explicit decision not to map scopes).</p>
 *
 * <p>The principal name is left at Spring's default (the JWT {@code sub} claim): a stable,
 * immutable identifier, well suited for logging and auditing. Only the authority derivation is
 * customized here.</p>
 *
 * <p>{@code SDIGITAL_KEYCLOAK_CLIENT_ROLE_IDS} (bound to
 * {@code sdigital.security.keycloak.client-role-ids}) lists the comma-separated client ids whose
 * {@code resource_access.<client-id>.roles} are trusted. It prevents roles advertised for
 * unrelated clients from becoming authorities.</p>
 */
@Configuration
public class KeycloakJwtAuthenticationConverterConfig {

    @Bean
    public JwtAuthenticationConverter keycloakJwtAuthenticationConverter(
            @Value("${sdigital.security.keycloak.client-role-ids:}") String clientRoleIds) {
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(
                new KeycloakRolesConverter(parseClientIds(clientRoleIds)));
        return authenticationConverter;
    }

    private static List<String> parseClientIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(clientId -> !clientId.isEmpty())
                .toList();
    }
}
