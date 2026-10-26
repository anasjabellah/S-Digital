package com.sdigital.platform.user.controller;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.common.security.KeycloakJwtAuthenticationConverterConfig;
import com.sdigital.platform.common.security.SecurityConfig;
import com.sdigital.platform.common.security.SecurityErrorResponseWriter;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.service.UserIdentityService;
import com.sdigital.platform.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security behaviour of {@code GET /api/v1/users/me} through the real
 * {@link SecurityConfig} filter chain.
 *
 * <p>Tokens are real RS256 JWTs signed with a test key pair, so signature and expiry are
 * actually validated. {@link UserService} and {@link UserIdentityService} are mocked: the
 * identity guard and the authorization rules are the behaviour under test.</p>
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, CurrentUserEndpointSecurityTest.TestJwtConfiguration.class})
@TestPropertySource(properties = "sdigital.security.cors.allowed-origins=")
class CurrentUserEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserIdentityService userIdentityService;

    private static UserResponse sample() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        return new UserResponse(UUID.randomUUID(), "alice@example.com", "Alice", "Doe",
                UserStatus.ACTIVE, null, null, now, now);
    }

    @Test
    void returns200WithOwnProfileForLinkedActiveIdentityWithoutRoleRestriction() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        when(userService.getCurrentUser()).thenReturn(sample());
        // No realm role: /me only requires authentication, not ADMIN/MANAGER.
        String token = TestJwtConfiguration.signedToken("test-user", Map.of());

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.keycloakId").doesNotExist());
    }

    @Test
    void returns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void returns401WhenTokenIsNotAJwt() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void returns401WhenTokenIsExpired() throws Exception {
        String token = TestJwtConfiguration.signedToken("test-user", Map.of(),
                Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void returns403WhenIdentityIsNotLinked() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        String token = TestJwtConfiguration.signedToken("test-user", Map.of());

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(SecurityErrorResponseWriter.DETAIL))
                .andExpect(content().string(not(containsString("test-user"))));

        verifyNoInteractions(userService);
    }

    @Test
    void returns403WhenLocalUserIsInactive() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        String token = TestJwtConfiguration.signedToken("test-user", Map.of());

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(SecurityErrorResponseWriter.DETAIL));

        verifyNoInteractions(userService);
    }

    @Test
    void getByIdStillRequiresAdminOrManagerRole() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        UUID id = UUID.randomUUID();
        String token = TestJwtConfiguration.signedToken("test-user", Map.of());

        mockMvc.perform(get("/api/v1/users/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    /**
     * Signs real RS256 tokens with a freshly generated key pair and exposes a matching decoder,
     * so the real resource-server validation runs without Keycloak.
     */
    @TestConfiguration
    static class TestJwtConfiguration {

        private static final KeyPair KEY_PAIR = generateKeyPair();

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
        }

        static String signedToken(String subject, Map<String, Object> extraClaims) {
            return signedToken(subject, extraClaims, Instant.now().minusSeconds(5), Instant.now().plusSeconds(3600));
        }

        static String signedToken(String subject, Map<String, Object> extraClaims,
                                  Instant issuedAt, Instant expiresAt) {
            JWK jwk = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                    .privateKey((RSAPrivateKey) KEY_PAIR.getPrivate())
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
