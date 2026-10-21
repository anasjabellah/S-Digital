package com.sdigital.platform.user.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sdigital.platform.user.keycloak.exception.KeycloakUpstreamException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

/**
 * Thin, purpose-built client over the Keycloak Admin REST API for <em>realm</em> role
 * management of users.
 *
 * <p>This class is the only place that talks HTTP to Keycloak. It deliberately
 * exposes a very small surface — look up a user, list a user's realm roles, add a
 * realm role, remove a realm role — and it never returns raw Keycloak payloads to
 * callers: only primitives and role names cross this boundary.</p>
 *
 * <p>Authentication uses the {@code client_credentials} grant of a dedicated
 * confidential client (service account). The access token is obtained on demand and
 * cached until shortly before expiry; the client secret is never logged.</p>
 *
 * <p>The class performs no automatic retry: role mutations are not idempotent from a
 * business standpoint, so a failed call is surfaced to the caller rather than
 * silently repeated.</p>
 */
public class KeycloakAdminClient {

    private static final ParameterizedTypeReference<List<RoleRepresentation>> ROLE_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final KeycloakAdminProperties properties;
    private final RestClient http;
    private final RestClient tokenClient;

    private volatile CachedToken cachedToken;

    public KeycloakAdminClient(KeycloakAdminProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.clone().baseUrl(trimTrailingSlash(properties.getServerUrl())).build();
        this.tokenClient = builder.clone().baseUrl(trimTrailingSlash(properties.getServerUrl())).build();
    }

    /**
     * Resolves the Keycloak user id for a linked {@code keycloakId} (the JWT {@code sub}).
     *
     * @return the Keycloak user id, or {@code null} when Keycloak has no such user
     */
    public String findUserId(String keycloakId) {
        List<UserRepresentation> users = http.get()
                .uri("/admin/realms/{realm}/users?exact=true&max=2&username={username}",
                        properties.getRealm(), keycloakId)
                .headers(this::authorize)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::translateUpstreamError)
                .body(new ParameterizedTypeReference<>() {
                });
        if (users == null || users.isEmpty()) {
            return null;
        }
        return users.get(0).id();
    }

    /**
     * Returns whether the Keycloak account is enabled. Only meaningful once the user id
     * has been resolved.
     */
    public boolean isUserEnabled(String keycloakUserId) {
        UserRepresentation user = http.get()
                .uri("/admin/realms/{realm}/users/{id}", properties.getRealm(), keycloakUserId)
                .headers(this::authorize)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::translateUpstreamError)
                .body(UserRepresentation.class);
        return user != null && Boolean.TRUE.equals(user.enabled());
    }

    /**
     * Lists the realm roles (names only) currently mapped to the user.
     */
    public List<String> listRealmRoles(String keycloakUserId) {
        List<RoleRepresentation> roles = http.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm",
                        properties.getRealm(), keycloakUserId)
                .headers(this::authorize)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::translateUpstreamError)
                .body(ROLE_LIST);
        if (roles == null) {
            return List.of();
        }
        return roles.stream().map(RoleRepresentation::name).filter(Objects::nonNull).toList();
    }

    /**
     * Adds a single realm role to the user. The role representation is resolved from its
     * name first so Keycloak can reject an unknown role with a clean error.
     *
     * @return {@code false} when no realm role with that name exists
     */
    public boolean addRealmRole(String keycloakUserId, String roleName) {
        RoleRepresentation role = findRealmRole(roleName);
        if (role == null) {
            return false;
        }
        http.post()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm",
                        properties.getRealm(), keycloakUserId)
                .headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::translateUpstreamError)
                .toBodilessEntity();
        return true;
    }

    /**
     * Removes a single realm role from the user.
     *
     * @return {@code false} when no realm role with that name exists
     */
    public boolean removeRealmRole(String keycloakUserId, String roleName) {
        RoleRepresentation role = findRealmRole(roleName);
        if (role == null) {
            return false;
        }
        http.method(org.springframework.http.HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm",
                        properties.getRealm(), keycloakUserId)
                .headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(role))
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::translateUpstreamError)
                .toBodilessEntity();
        return true;
    }

    private RoleRepresentation findRealmRole(String roleName) {
        RoleRepresentation role = http.get()
                .uri("/admin/realms/{realm}/roles/{roleName}", properties.getRealm(), roleName)
                .headers(this::authorize)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                })
                .onStatus(status -> status.value() != 404 && !status.is2xxSuccessful(), this::translateUpstreamError)
                .body(RoleRepresentation.class);
        return role;
    }

    /**
     * Translates a non-2xx Keycloak Admin response into a {@link KeycloakUpstreamException}
     * classified by status, without reading or forwarding the Keycloak response body.
     */
    private void translateUpstreamError(org.springframework.http.HttpRequest request,
                                        org.springframework.http.client.ClientHttpResponse response)
            throws java.io.IOException {
        int statusCode = response.getStatusCode().value();
        KeycloakUpstreamException.Kind kind;
        if (statusCode == HttpStatus.UNAUTHORIZED.value()) {
            kind = KeycloakUpstreamException.Kind.UNAUTHENTICATED;
        } else if (statusCode == HttpStatus.FORBIDDEN.value()) {
            kind = KeycloakUpstreamException.Kind.FORBIDDEN;
        } else if (statusCode == HttpStatus.TOO_MANY_REQUESTS.value()) {
            kind = KeycloakUpstreamException.Kind.RATE_LIMITED;
        } else {
            kind = KeycloakUpstreamException.Kind.SERVER_ERROR;
        }
        throw new KeycloakUpstreamException(kind, statusCode,
                "Keycloak admin request failed with status " + statusCode);
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(currentAccessToken());
    }

    /**
     * Returns a valid service-account access token, refreshing it when close to expiry.
     */
    private String currentAccessToken() {
        CachedToken current = cachedToken;
        long now = System.currentTimeMillis();
        if (current != null && now < current.expiresAtMillis()) {
            return current.value();
        }
        TokenResponse response = tokenClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(tokenForm())
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), this::translateUpstreamError)
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new KeycloakUpstreamException(KeycloakUpstreamException.Kind.SERVER_ERROR, 200,
                    "Keycloak returned no access token");
        }
        long expiresInSeconds = response.expiresIn() != null ? response.expiresIn() : 60L;
        // Refresh 30 seconds before the real expiry to avoid using an about-to-expire token.
        long expiresAt = now + Math.max(0, expiresInSeconds - 30) * 1000L;
        CachedToken fresh = new CachedToken(response.accessToken(), expiresAt);
        cachedToken = fresh;
        return fresh.value();
    }

    private MultiValueMap<String, String> tokenForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        return form;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record CachedToken(String value, long expiresAtMillis) {
    }

    /**
     * Shape of the token endpoint response (only the fields we use).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn
    ) {
    }

    /**
     * Minimal Keycloak user representation (only the fields we use).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UserRepresentation(
            String id,
            Boolean enabled
    ) {
    }

    /**
     * Minimal Keycloak role representation (only the fields we use).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RoleRepresentation(
            String id,
            String name
    ) {
    }
}
