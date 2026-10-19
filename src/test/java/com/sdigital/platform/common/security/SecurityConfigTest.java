package com.sdigital.platform.common.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.controller.UserController;
import com.sdigital.platform.user.service.UserService;
import com.sdigital.platform.user.service.UserIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real {@link SecurityConfig} filter chain against {@link UserController}.
 *
 * <p>Tokens are real RS256 JWTs signed with a test key pair, so signature and expiry are
 * actually validated (as they would be against Keycloak's JWKS) without running a Keycloak
 * server. {@link UserService} is mocked: only the security behaviour is asserted here.</p>
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, SecurityConfigTest.TestJwtConfiguration.class})
@TestPropertySource(properties = "sdigital.security.cors.allowed-origins=https://app.example.com")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserIdentityService userIdentityService;

    @Test
    void businessEndpointReturns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessEndpointReturns401WhenTokenIsNotAJwt() throws Exception {
        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessEndpointReturns401WhenTokenIsExpired() throws Exception {
        String token = TestJwtConfiguration.signedToken(
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessEndpointReturns401WhenTokenSignatureIsInvalid() throws Exception {
        String token = TestJwtConfiguration.signedTokenWithForeignKey(
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessEndpointReturns200WithValidToken() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        when(userService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        String token = TestJwtConfiguration.signedTokenWithRealmRole("MANAGER");

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void businessEndpointReturns403ForValidTokenWithoutRealmRole() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        String token = TestJwtConfiguration.signedToken(
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.type").value(SecurityErrorResponseWriter.TYPE_FORBIDDEN.toString()))
                .andExpect(jsonPath("$.title").value(SecurityErrorResponseWriter.TITLE))
                .andExpect(content().string(not(containsString("test-user"))));

        verifyNoInteractions(userService);
    }

    @Test
    void businessEndpointReturns403WhenTokenIsNotLinkedToActiveLocalUser() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        String token = TestJwtConfiguration.signedTokenWithRealmRole("ADMIN");

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(SecurityErrorResponseWriter.DETAIL))
                .andExpect(content().string(not(containsString("ADMIN"))));
    }

    @Test
    void corsPreflightIsHandledWithoutToken() throws Exception {
        mockMvc.perform(options("/api/v1/users")
                        .header(HttpHeaders.ORIGIN, "https://app.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.com"));
    }

    /**
     * Provides a {@link JwtDecoder} bound to a freshly generated RSA key pair, so the real
     * security chain can validate locally signed test tokens.
     */
    @TestConfiguration
    static class TestJwtConfiguration {

        private static final KeyPair KEY_PAIR = generateKeyPair();
        private static final KeyPair FOREIGN_KEY_PAIR = generateKeyPair();

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
        }

        static String signedToken(Instant issuedAt, Instant expiresAt) {
            return encode(KEY_PAIR, issuedAt, expiresAt);
        }

        /**
         * Signs a valid token carrying a single Keycloak realm role, as the resource server would
         * receive from Keycloak. Used for the requests that must pass the RBAC rules of
         * {@link SecurityConfig} in addition to the identity guard.
         */
        static String signedTokenWithRealmRole(String realmRole) {
            return encodeWithRealmRole(KEY_PAIR, realmRole,
                    Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        }

        static String signedTokenWithSubject(String subject, Instant issuedAt, Instant expiresAt) {
            return encodeWithSubject(KEY_PAIR, subject, issuedAt, expiresAt);
        }

        static String signedTokenWithForeignKey(Instant issuedAt, Instant expiresAt) {
            return encode(FOREIGN_KEY_PAIR, issuedAt, expiresAt);
        }

        private static String encode(KeyPair keyPair, Instant issuedAt, Instant expiresAt) {
            return encodeWithSubject(keyPair, "test-user", issuedAt, expiresAt);
        }

        private static String encodeWithRealmRole(KeyPair keyPair, String realmRole,
                                                  Instant issuedAt, Instant expiresAt) {
            return encode(keyPair, "test-user", Map.of(
                    "realm_access", Map.of("roles", List.of(realmRole))), issuedAt, expiresAt);
        }

        private static String encodeWithSubject(KeyPair keyPair, String subject, Instant issuedAt, Instant expiresAt) {
            return encode(keyPair, subject, Map.of(), issuedAt, expiresAt);
        }

        private static String encode(KeyPair keyPair, String subject, Map<String, Object> extraClaims,
                                     Instant issuedAt, Instant expiresAt) {
            JWK jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID("test-key")
                    .build();
            NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
            JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                    .issuer("https://keycloak.test/realms/s-digital")
                    .subject(subject)
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
