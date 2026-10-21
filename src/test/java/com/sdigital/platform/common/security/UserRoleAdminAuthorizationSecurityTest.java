package com.sdigital.platform.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.controller.UserRoleController;
import com.sdigital.platform.user.dto.AssignRoleRequest;
import com.sdigital.platform.user.dto.UserRolesResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.keycloak.KeycloakRoleAdminService;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.service.UserIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts that the /api/v1/users/{id}/roles endpoints are ADMIN-only through the real
 * {@link SecurityConfig} filter chain, and that MANAGER/USER are denied.
 */
@WebMvcTest(controllers = UserRoleController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class UserRoleAdminAuthorizationSecurityTest {

    private static final String KEYCLOAK_SUB = "test-user";
    private static final UUID USER_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KeycloakRoleAdminService keycloakRoleAdminService;

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
    void adminCanReadAndWriteRoles() throws Exception {
        when(keycloakRoleAdminService.getRoles(USER_ID)).thenReturn(new UserRolesResponse(List.of()));
        when(keycloakRoleAdminService.assignRole(USER_ID, "MANAGER"))
                .thenReturn(new UserRolesResponse(List.of()));
        when(keycloakRoleAdminService.removeRole(USER_ID, "MANAGER"))
                .thenReturn(new UserRolesResponse(List.of()));

        mockMvc.perform(get("/api/v1/users/" + USER_ID + "/roles")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/" + USER_ID + "/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRoleRequest("MANAGER")))
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/users/" + USER_ID + "/roles/MANAGER")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void managerIsDeniedRolesAccess() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/users/" + USER_ID + "/roles")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbidden(mockMvc.perform(post("/api/v1/users/" + USER_ID + "/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new AssignRoleRequest("MANAGER")))
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbidden(mockMvc.perform(delete("/api/v1/users/" + USER_ID + "/roles/MANAGER")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));

        verifyNoInteractions(keycloakRoleAdminService);
    }

    @Test
    void userIsDeniedRolesAccess() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/users/" + USER_ID + "/roles")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));

        verifyNoInteractions(keycloakRoleAdminService);
    }

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + USER_ID + "/roles"))
                .andExpect(status().isUnauthorized());
    }

    private static ResultActions expectForbidden(ResultActions actions) throws Exception {
        return actions
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private static String bearer(String realmRole) {
        return "Bearer " + KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("realm_access", Map.of("roles", List.of(realmRole))));
    }
}
