package com.sdigital.platform.common.security;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.dashboard.controller.DashboardController;
import com.sdigital.platform.dashboard.dto.DashboardSummaryResponse;
import com.sdigital.platform.dashboard.dto.EntityStatusStatistics;
import com.sdigital.platform.dashboard.service.DashboardService;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.service.UserIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the RBAC policy applied to {@code GET /api/v1/dashboard/summary} through the real
 * filter chain: bearer JWT -> {@code JwtDecoder} -> {@link KeycloakRolesConverter} ->
 * {@link LocalUserActiveGuardFilter} -> the authorization filter.
 *
 * <p>Policy under test: the endpoint is shared by {@code ROLE_ADMIN} and {@code ROLE_MANAGER},
 * refused to {@code ROLE_USER}, never public, and a role never compensates for a missing or
 * INACTIVE local identity.</p>
 */
@WebMvcTest(controllers = DashboardController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class DashboardAuthorizationSecurityTest {

    /** {@code sub} claim signed by the test tokens, therefore the linked local keycloakId. */
    private static final String KEYCLOAK_SUB = "test-user";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void linkedActiveLocalUser() {
        User user = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        user.setKeycloakId(KEYCLOAK_SUB);
        user.setStatus(UserStatus.ACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(user));

        lenient().when(dashboardService.getSummary()).thenReturn(new DashboardSummaryResponse(
                new EntityStatusStatistics(1, 1, 0),
                new EntityStatusStatistics(1, 1, 0),
                new EntityStatusStatistics(1, 1, 0),
                Instant.parse("2026-10-21T10:30:00Z")));
    }

    @Test
    void adminIsAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void managerIsAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary").header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void userRoleIsDenied() throws Exception {
        expectForbiddenJson(mockMvc.perform(get("/api/v1/dashboard/summary")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void tokenWithoutBusinessRoleIsDenied() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("scope", "profile email"));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/dashboard/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void unlinkedLocalUserIsDeniedByTheGuard() throws Exception {
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());

        expectForbiddenJson(mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void inactiveLocalUserIsDeniedByTheGuard() throws Exception {
        User inactive = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        inactive.setKeycloakId(KEYCLOAK_SUB);
        inactive.setStatus(UserStatus.INACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(inactive));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(dashboardService);
    }

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private static ResultActions expectForbiddenJson(ResultActions actions) throws Exception {
        return actions
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("""
                        {"type":"https://s-digital.io/errors/forbidden",
                         "title":"Forbidden",
                         "status":403,
                         "detail":"Access denied"}
                        """, true));
    }

    private static String bearer(String realmRole) {
        return "Bearer " + KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("realm_access", Map.of("roles", List.of(realmRole))));
    }
}
