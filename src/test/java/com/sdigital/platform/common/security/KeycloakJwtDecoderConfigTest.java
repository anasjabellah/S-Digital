package com.sdigital.platform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KeycloakJwtDecoderConfig}.
 *
 * <p>No Spring context and no Keycloak server: they verify the fail-closed behaviour when
 * the issuer or the audience is missing, and that a configured issuer/audience yields a
 * Nimbus decoder built without contacting Keycloak eagerly (so the application can start
 * while Keycloak is down).</p>
 */
class KeycloakJwtDecoderConfigTest {

    private static final String ISSUER = "http://localhost:59999/realms/s-digital";
    private static final String AUDIENCE = "s-digital-api";

    private final KeycloakJwtDecoderConfig config = new KeycloakJwtDecoderConfig();

    @Test
    void rejectsEveryTokenWhenIssuerIsNotConfigured() {
        JwtDecoder decoder = config.jwtDecoder("", AUDIENCE);

        assertThatThrownBy(() -> decoder.decode("any.token.value"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("KEYCLOAK_ISSUER_URI");
    }

    @Test
    void rejectsEveryTokenWhenIssuerIsBlank() {
        JwtDecoder decoder = config.jwtDecoder("   ", AUDIENCE);

        assertThatThrownBy(() -> decoder.decode("any.token.value"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsEveryTokenWhenAudienceIsNotConfigured() {
        JwtDecoder decoder = config.jwtDecoder(ISSUER, "");

        assertThatThrownBy(() -> decoder.decode("any.token.value"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("KEYCLOAK_AUDIENCE");
    }

    @Test
    void rejectsEveryTokenWhenAudienceIsBlank() {
        JwtDecoder decoder = config.jwtDecoder(ISSUER, "   ");

        assertThatThrownBy(() -> decoder.decode("any.token.value"))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("KEYCLOAK_AUDIENCE");
    }

    @Test
    void buildsNimbusDecoderWithoutContactingKeycloakWhenIssuerAndAudienceAreConfigured() {
        // Port 59999 is not expected to be listening: if the decoder resolved the JWK set
        // eagerly, building it (or this test) would fail. Lazy resolution keeps startup
        // independent of Keycloak availability.
        JwtDecoder decoder = config.jwtDecoder(ISSUER, AUDIENCE);

        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }
}
