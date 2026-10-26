package com.sdigital.platform.user.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration for the Keycloak Admin integration used to provision users and manage their
 * realm roles.
 *
 * <p>Every value, including the client secret, is supplied through environment
 * variables (see {@code application.yml}); nothing is hard-coded and no secret is
 * ever committed to Git.</p>
 *
 * <p>When required values are missing the configuration is considered
 * <em>not configured</em>: the provisioning and role-management endpoints then fail closed
 * instead of attempting a partial or insecure call. This is checked explicitly by
 * {@link #isConfigured()} rather than by letting a null leak into the HTTP client.</p>
 */
@ConfigurationProperties(prefix = "sdigital.keycloak.admin")
public class KeycloakAdminProperties {

    /**
     * Base URL of the Keycloak server, e.g. {@code http://localhost:8180}.
     */
    private String serverUrl;

    /**
     * Realm that owns the target users and the service-account client.
     */
    private String realm;

    /**
     * Confidential client id used by the backend (service account).
     */
    private String clientId;

    /**
     * Client secret of the confidential client. Never logged, never returned.
     */
    private String clientSecret;

    /**
     * Realm roles the API is allowed to assign or remove, comma-separated.
     *
     * <p>{@code ADMIN} must never appear here: ADMIN assignment is refused by
     * business rule regardless of configuration.</p>
     */
    private List<String> allowlist = List.of();

    /**
     * Connect timeout for Admin REST calls.
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * Read timeout for Admin REST calls.
     */
    private Duration readTimeout = Duration.ofSeconds(5);

    public boolean isConfigured() {
        return StringUtils.hasText(serverUrl)
                && StringUtils.hasText(realm)
                && StringUtils.hasText(clientId)
                && StringUtils.hasText(clientSecret);
    }

    /**
     * Returns the allowlist as a de-duplicated, trimmed set of names.
     */
    public Set<String> allowlistAsSet() {
        Set<String> cleaned = new LinkedHashSet<>();
        if (allowlist != null) {
            for (String role : allowlist) {
                if (StringUtils.hasText(role)) {
                    cleaned.add(role.trim());
                }
            }
        }
        return cleaned;
    }

    public boolean isAllowlistConfigured() {
        return !allowlistAsSet().isEmpty();
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public List<String> getAllowlist() {
        return allowlist;
    }

    /**
     * Binds a comma-separated string (or a list) to the allowlist.
     */
    public void setAllowlist(List<String> allowlist) {
        this.allowlist = allowlist;
    }

    /**
     * Convenience setter so a comma-separated environment variable binds cleanly.
     */
    public void setAllowlist(String csv) {
        if (csv == null) {
            this.allowlist = List.of();
            return;
        }
        this.allowlist = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
