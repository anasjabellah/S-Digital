package com.sdigital.platform.user.keycloak;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the Keycloak Admin integration.
 *
 * <p>The {@link KeycloakAdminClient} bean is always created (so the application
 * context is stable and testable), but it fails closed at call time when the
 * configuration is incomplete: {@link KeycloakAdminProperties#isConfigured()} is
 * checked by the service before any call. No Keycloak connection is attempted at
 * startup.</p>
 *
 * <p>The configured {@code connectTimeout} and {@code readTimeout} are applied to the
 * {@link RestClient.Builder} used by the client so a slow or unreachable Keycloak
 * cannot hold backend request threads indefinitely.</p>
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

    @Bean
    public KeycloakAdminClient keycloakAdminClient(KeycloakAdminProperties properties,
                                                   RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(properties.getConnectTimeout())
                        .withReadTimeout(properties.getReadTimeout()));
        return new KeycloakAdminClient(properties, restClientBuilder.requestFactory(requestFactory));
    }
}
