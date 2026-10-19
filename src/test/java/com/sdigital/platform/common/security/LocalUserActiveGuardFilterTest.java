package com.sdigital.platform.common.security;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.controller.UserController;
import com.sdigital.platform.user.service.UserIdentityService;
import com.sdigital.platform.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@link LocalUserActiveGuardFilter} through the real {@link SecurityConfig} filter chain.
 *
 * <p>The guard must be evaluated <em>after</em> bearer authentication: only a JWT already validated
 * by the resource server reaches the identity check. Requests without a token, with a malformed or
 * expired token, or signed with an unknown key are rejected with 401 by the resource server and
 * never consult {@link UserIdentityService}.</p>
 *
 * <p>Token signing uses the test keys of
 * {@link KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration}, so signature and expiry are
 * actually validated. {@link UserService} is mocked: only security behaviour is asserted here.
 * The role matrix itself is asserted by {@link UserRoleAuthorizationSecurityTest}; the only
 * role-related expectation here is that a role never changes the guard's identity decision.</p>
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = "sdigital.security.cors.allowed-origins=https://app.example.com")
class LocalUserActiveGuardFilterTest {

    private static final String USERS_ENDPOINT = "/api/v1/users";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserIdentityService userIdentityService;

    // --- No token / invalid token -> 401, identity never consulted ---------------------------

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(USERS_ENDPOINT))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userIdentityService);
    }

    @Test
    void malformedJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(USERS_ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userIdentityService);
    }

    @Test
    void expiredJwtReturnsUnauthorized() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of(), Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        mockMvc.perform(get(USERS_ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userIdentityService);
    }

    @Test
    void foreignSignatureReturnsUnauthorized() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration
                .signedTokenWithForeignKey(Map.of());

        mockMvc.perform(get(USERS_ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userIdentityService);
    }

    // --- Valid JWT but no linked ACTIVE local user -> 403 ------------------------------------

    @Test
    void validJwtWithoutLinkedLocalUserReturns403() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        String token = validToken();

        expectForbiddenJson(mockMvc.perform(get(USERS_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .andExpect(content().string(not(containsString("test-user"))))
                .andExpect(content().string(not(containsString("Local linked"))));
    }

    @Test
    void validJwtWithLinkedInactiveLocalUserReturns403() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        String token = validToken();

        expectForbiddenJson(mockMvc.perform(get(USERS_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
    }

    @Test
    void realmAdminRoleDoesNotBypassIdentityGuard() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("realm_access", Map.of("roles", List.of("ADMIN"))));

        expectForbiddenJson(mockMvc.perform(get(USERS_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .andExpect(content().string(not(containsString("ADMIN"))));
    }

    @Test
    void identityResolutionFailureFailsClosedTo403() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked())
                .thenThrow(new IllegalStateException("secret infrastructure detail"));
        String token = validToken();

        expectForbiddenJson(mockMvc.perform(get(USERS_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)))
                .andExpect(content().string(not(containsString("secret infrastructure detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    // --- Linked + ACTIVE local user -> allowed ------------------------------------------------

    @Test
    void validJwtWithLinkedActiveLocalUserIsAllowed() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        when(userService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        String token = realmRoleToken("MANAGER");

        mockMvc.perform(get(USERS_ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * The guard and the RBAC rules answer different questions: the guard only checks that the
     * identity is linked and ACTIVE, the role rules decide what that identity may do. A linked
     * ACTIVE user without the required realm role is refused by the authorization filter with
     * exactly the same JSON representation as a guard refusal.
     */
    @Test
    void activeUserWithoutRequiredRealmRoleIsDeniedByAuthorizationWithForbiddenJson() throws Exception {
        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        String token = validToken();

        expectForbiddenJson(mockMvc.perform(get(USERS_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));

        verify(userIdentityService).isCurrentUserActiveLinked();
        verifyNoInteractions(userService);
    }

    @Test
    void guardDenialAndRoleDenialReturnTheSameJsonBody() throws Exception {
        String token = validToken();

        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(false);
        MvcResult guardDenial = mockMvc.perform(get(USERS_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();

        when(userIdentityService.isCurrentUserActiveLinked()).thenReturn(true);
        MvcResult roleDenial = mockMvc.perform(get(USERS_ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();

        assertThat(guardDenial.getResponse().getStatus()).isEqualTo(403);
        assertThat(roleDenial.getResponse().getStatus()).isEqualTo(403);
        assertThat(guardDenial.getResponse().getContentType())
                .isEqualTo(roleDenial.getResponse().getContentType());
        assertThat(guardDenial.getResponse().getContentAsString())
                .isEqualTo(roleDenial.getResponse().getContentAsString());
    }

    // --- Public endpoints and CORS preflight stay exempt --------------------------------------

    @Test
    void publicOpenApiPathIsNotBlockedByGuard() throws Exception {
        String token = validToken();

        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));

        verifyNoInteractions(userIdentityService);
    }

    @Test
    void publicHealthPathIsNotBlockedByGuard() throws Exception {
        String token = validToken();

        mockMvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));

        verifyNoInteractions(userIdentityService);
    }

    @Test
    void corsPreflightIsNotBlockedByGuard() throws Exception {
        String token = validToken();

        mockMvc.perform(options(USERS_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(HttpHeaders.ORIGIN, "https://app.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://app.example.com"));

        verifyNoInteractions(userIdentityService);
    }

    private static ResultActions expectForbiddenJson(ResultActions actions) throws Exception {
        return actions
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"type":"https://s-digital.io/errors/forbidden",
                         "title":"Forbidden",
                         "status":403,
                         "detail":"Access denied"}
                        """, true));
    }

    private static String validToken() {
        return KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(Map.of());
    }

    private static String realmRoleToken(String realmRole) {
        return KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("realm_access", Map.of("roles", List.of(realmRole))));
    }
}
