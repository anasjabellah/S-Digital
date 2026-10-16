package com.sdigital.platform.common.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies Keycloak role mapping through the real {@link SecurityConfig} filter chain.
 *
 * <p>Unlike {@link KeycloakRolesConverterTest} (which exercises the converter in isolation), here
 * the tokens are real RS256 JWTs signed with a test key and validated by the resource server, then
 * passed through the configured {@link KeycloakJwtAuthenticationConverterConfig}. A tiny test-only
 * controller echoes the resulting authorities, so the full path
 * {@code bearer token -> JwtDecoder -> JwtAuthenticationConverter -> SecurityContext} is asserted
 * end to end. No production endpoint is added or modified.</p>
 */
@WebMvcTest(controllers = KeycloakRolesSecurityIntegrationTest.AuthoritiesController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, KeycloakRolesSecurityIntegrationTest.AuthoritiesController.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.keycloak.client-role-ids=s-digital-api",
        "sdigital.security.cors.allowed-origins="
})
class KeycloakRolesSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validTokenWithRealmRoleYieldsRoleAuthority() throws Exception {
        String token = TestJwtConfiguration.signedToken(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN"))));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"ROLE_ADMIN\"]"));
    }

    @Test
    void validTokenWithTrustedClientRoleYieldsNamespacedClientAuthority() throws Exception {
        String token = TestJwtConfiguration.signedToken(Map.of(
                "resource_access", Map.of("s-digital-api", Map.of("roles", List.of("viewer")))));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"CLIENT_s-digital-api_viewer\"]"));
    }

    @Test
    void validTokenWithRealmAndClientRolesYieldsBothAuthorities() throws Exception {
        String token = TestJwtConfiguration.signedToken(Map.of(
                "realm_access", Map.of("roles", List.of("MANAGER")),
                "resource_access", Map.of("s-digital-api", Map.of("roles", List.of("editor")))));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[\"CLIENT_s-digital-api_editor\",\"ROLE_MANAGER\"]"));
    }

    @Test
    void validTokenWithoutRolesIsAuthenticatedButGetsNoAuthority() throws Exception {
        String token = TestJwtConfiguration.signedToken(Map.of("scope", "profile email"));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void clientRoleOfUntrustedClientYieldsNoAuthorityThroughTheChain() throws Exception {
        String token = TestJwtConfiguration.signedToken(Map.of(
                "resource_access", Map.of("some-other-client", Map.of("roles", List.of("admin")))));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void unknownTopLevelRolesClaimYieldsNoAuthorityThroughTheChain() throws Exception {
        String token = TestJwtConfiguration.signedToken(Map.of("roles", List.of("ADMIN")));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // --- Scenario 6: invalid token -> security behaviour unchanged ---------------------------

    @Test
    void protectedEndpointReturns401WhenTokenSignatureIsInvalid() throws Exception {
        String token = TestJwtConfiguration.signedTokenWithForeignKey(Map.of(
                "realm_access", Map.of("roles", List.of("ADMIN"))));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointReturns401WhenTokenIsExpired() throws Exception {
        String token = TestJwtConfiguration.signedToken(
                Map.of("realm_access", Map.of("roles", List.of("ADMIN"))),
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointReturns401WhenTokenIsNotAJwt() throws Exception {
        mockMvc.perform(get("/api/v1/test/authorities").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    // --- Scenario 7: protected endpoint still refuses anonymous access -----------------------

    @Test
    void protectedEndpointReturns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/test/authorities"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test-only endpoint that reflects the authorities granted to the authenticated caller. It is
     * not part of the application and is never exposed at runtime.
     */
    @RestController
    static class AuthoritiesController {

        @GetMapping("/api/v1/test/authorities")
        List<String> authorities(Authentication authentication) {
            return authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .sorted()
                    .toList();
        }
    }

    /**
     * Signs real RS256 test tokens with a freshly generated key pair and exposes a matching
     * {@link JwtDecoder}, so the real resource-server validation runs without Keycloak.
     */
    @TestConfiguration
    static class TestJwtConfiguration {

        private static final KeyPair KEY_PAIR = generateKeyPair();
        private static final KeyPair FOREIGN_KEY_PAIR = generateKeyPair();

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
        }

        static String signedToken(Map<String, Object> extraClaims) {
            return encode(KEY_PAIR, extraClaims, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        }

        static String signedToken(Map<String, Object> extraClaims, Instant issuedAt, Instant expiresAt) {
            return encode(KEY_PAIR, extraClaims, issuedAt, expiresAt);
        }

        static String signedTokenWithForeignKey(Map<String, Object> extraClaims) {
            return encode(FOREIGN_KEY_PAIR, extraClaims, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        }

        private static String encode(KeyPair keyPair, Map<String, Object> extraClaims,
                                     Instant issuedAt, Instant expiresAt) {
            JWK jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("test-key")
                    .build();
            NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer("https://keycloak.test/realms/s-digital")
                    .subject("test-user")
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt);
            extraClaims.forEach(claims::claim);
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
            return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        }

        private static KeyPair generateKeyPair() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return generator.generateKeyPair();
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot generate an RSA key pair for tests", ex);
            }
        }
    }
}
