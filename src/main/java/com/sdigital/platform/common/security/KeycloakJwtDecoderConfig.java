package com.sdigital.platform.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * Builds the {@link JwtDecoder} that validates bearer tokens against Keycloak.
 *
 * <p>The realm issuer is supplied through the {@code KEYCLOAK_ISSUER_URI} environment
 * variable (bound to {@code sdigital.security.keycloak.issuer-uri}). The decoder is built
 * from the realm's JWKS endpoint ({@code <issuer>/protocol/openid-connect/certs}) and is
 * resolved lazily, so the application starts even when Keycloak is not reachable; the JWK
 * set is only fetched when the first token is validated. In addition to the signature and
 * the temporal claims ({@code exp}/{@code nbf}), the issuer claim is validated.</p>
 *
 * <p>When {@code KEYCLOAK_ISSUER_URI} is not set, a fail-closed decoder is provided: the
 * API still starts but rejects every bearer token (business endpoints answer {@code 401}).
 * This keeps local development and the test suite independent of a running Keycloak while
 * never exposing business endpoints anonymously.</p>
 */
@Configuration
public class KeycloakJwtDecoderConfig {

    private static final Logger log = LoggerFactory.getLogger(KeycloakJwtDecoderConfig.class);

    /**
     * Keycloak realm JWKS path, relative to the issuer URL.
     */
    private static final String KEYCLOAK_JWK_SET_PATH = "/protocol/openid-connect/certs";

    @Bean
    public JwtDecoder jwtDecoder(@Value("${sdigital.security.keycloak.issuer-uri:}") String issuerUri) {
        if (!StringUtils.hasText(issuerUri)) {
            log.warn("KEYCLOAK_ISSUER_URI is not set: the API will start but reject every bearer token "
                    + "(business endpoints answer 401). Set KEYCLOAK_ISSUER_URI to the Keycloak realm "
                    + "issuer (e.g. http://localhost:8180/realms/s-digital) to enable authentication.");
            return rejectedToken -> {
                throw new JwtException("KEYCLOAK_ISSUER_URI is not configured; the token cannot be validated.");
            };
        }

        String issuer = trimTrailingSlashes(issuerUri);
        String jwkSetUri = issuer + KEYCLOAK_JWK_SET_PATH;

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        log.info("Keycloak JWT validation enabled for issuer '{}' (JWKS resolved lazily from '{}').",
                issuer, jwkSetUri);
        return decoder;
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
