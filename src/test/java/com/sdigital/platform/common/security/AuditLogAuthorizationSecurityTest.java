package com.sdigital.platform.common.security;

import com.sdigital.platform.audit.controller.AuditLogController;
import com.sdigital.platform.audit.service.AuditLogQueryService;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.service.UserIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts that the /api/v1/audit-logs endpoint is ADMIN-only through the real
 * {@link SecurityConfig} filter chain, and that MANAGER/USER are denied.
 *
 * <p>The audit trail exposes the history of every privileged action of the platform, so
 * it is deliberately not shared with MANAGER, whose read access covers the business data
 * only. The centralized mechanisms are exercised as they are: JWT validation, the local
 * identity guard and the RBAC rules of {@link SecurityConfig}.</p>
 */
@WebMvcTest(controllers = AuditLogController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class AuditLogAuthorizationSecurityTest {

    private static final String KEYCLOAK_SUB = "test-user";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void linkedActiveLocalUser() {
        User user = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        user.setKeycloakId(KEYCLOAK_SUB);
        user.setStatus(UserStatus.ACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(user));
    }

    @Test
    void adminCanConsultTheAuditTrail() throws Exception {
        when(auditLogQueryService.search(any(), any())).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void managerIsDeniedAuditAccess() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/audit-logs")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));

        verifyNoInteractions(auditLogQueryService);
    }

    @Test
    void userIsDeniedAuditAccess() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/audit-logs")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));

        verifyNoInteractions(auditLogQueryService);
    }

    @Test
    void authenticatedUserWithoutAnyRoleIsDeniedAuditAccess() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/audit-logs")
                .header(HttpHeaders.AUTHORIZATION, bearer(null))));

        verifyNoInteractions(auditLogQueryService);
    }

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(auditLogQueryService);
    }

    /**
     * The local identity guard runs before the authorization filter: a valid ADMIN token
     * whose identity is not linked to an ACTIVE local user is refused as well.
     */
    @Test
    void adminTokenIsDeniedWhenLocalIdentityIsNotLinkedAndActive() throws Exception {
        when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());

        expectForbidden(mockMvc.perform(get("/api/v1/audit-logs")
                .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))));

        verifyNoInteractions(auditLogQueryService);
    }

    private static ResultActions expectForbidden(ResultActions actions) throws Exception {
        return actions
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private static String bearer(String realmRole) {
        Map<String, Object> claims = realmRole == null
                ? Map.of()
                : Map.of("realm_access", Map.of("roles", List.of(realmRole)));
        return "Bearer " + KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(claims);
    }
}
