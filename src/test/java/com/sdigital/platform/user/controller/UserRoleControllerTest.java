package com.sdigital.platform.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.dto.AssignRoleRequest;
import com.sdigital.platform.user.dto.UserRoleDto;
import com.sdigital.platform.user.dto.UserRolesResponse;
import com.sdigital.platform.user.exception.UserNotFoundException;
import com.sdigital.platform.user.keycloak.KeycloakRoleAdminService;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.keycloak.exception.RoleChangeDeniedException;
import com.sdigital.platform.user.keycloak.exception.UserNotLinkedToKeycloakException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer tests for {@link UserRoleController} and the new {@link GlobalExceptionHandler}
 * mappings. The service is mocked; security filters are disabled here and covered by
 * {@code UserRoleAdminAuthorizationSecurityTest}.
 */
@WebMvcTest(controllers = UserRoleController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserRoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KeycloakRoleAdminService keycloakRoleAdminService;

    private static UserRolesResponse roles(String... names) {
        return new UserRolesResponse(java.util.Arrays.stream(names).map(UserRoleDto::new).toList());
    }

    @Test
    void getRolesReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.getRoles(id)).thenReturn(roles("MANAGER"));

        mockMvc.perform(get("/api/v1/users/{id}/roles", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].name").value("MANAGER"));
    }

    @Test
    void getRolesReturns404WhenUserMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.getRoles(id)).thenThrow(new UserNotFoundException(id));

        mockMvc.perform(get("/api/v1/users/{id}/roles", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User not found"));
    }

    @Test
    void getRolesReturns422WhenNotLinked() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.getRoles(id)).thenThrow(new UserNotLinkedToKeycloakException(id));

        mockMvc.perform(get("/api/v1/users/{id}/roles", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("User is not linked to a Keycloak account"));
    }

    @Test
    void getRolesReturns503WhenKeycloakUnavailable() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.getRoles(id)).thenThrow(new KeycloakUnavailableException("internal"));

        mockMvc.perform(get("/api/v1/users/{id}/roles", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", not(containsString("internal"))));
    }

    @Test
    void assignRoleReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.assignRole(eq(id), eq("MANAGER"))).thenReturn(roles("MANAGER"));
        String body = objectMapper.writeValueAsString(new AssignRoleRequest("MANAGER"));

        mockMvc.perform(post("/api/v1/users/{id}/roles", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].name").value("MANAGER"));
    }

    @Test
    void assignRoleReturns400WhenRoleNameBlank() throws Exception {
        UUID id = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new AssignRoleRequest(""));

        mockMvc.perform(post("/api/v1/users/{id}/roles", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignRoleReturns403WhenDenied() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.assignRole(any(), any()))
                .thenThrow(new RoleChangeDeniedException("denied"));
        String body = objectMapper.writeValueAsString(new AssignRoleRequest("ADMIN"));

        mockMvc.perform(post("/api/v1/users/{id}/roles", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Role change is not allowed"));
    }

    @Test
    void removeRoleReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.removeRole(eq(id), eq("MANAGER"))).thenReturn(roles());

        mockMvc.perform(delete("/api/v1/users/{id}/roles/{roleName}", id, "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void removeRoleReturns400WhenUuidMalformed() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{id}/roles/{roleName}", "not-a-uuid", "MANAGER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void assignRoleReturns403WhenKeycloakAccountDisabled() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.assignRole(any(), any()))
                .thenThrow(new RoleChangeDeniedException("The Keycloak account is disabled"));
        String body = objectMapper.writeValueAsString(new AssignRoleRequest("MANAGER"));

        mockMvc.perform(post("/api/v1/users/{id}/roles", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Role change is not allowed"))
                .andExpect(jsonPath("$.detail", not(containsString("disabled"))));
    }

    @Test
    void removeRoleReturns403WhenKeycloakAccountDisabled() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.removeRole(any(), any()))
                .thenThrow(new RoleChangeDeniedException("The Keycloak account is disabled"));

        mockMvc.perform(delete("/api/v1/users/{id}/roles/{roleName}", id, "MANAGER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Role change is not allowed"))
                .andExpect(jsonPath("$.detail", not(containsString("disabled"))));
    }

    @Test
    void assignRoleReturns503WhenKeycloakUnavailableOnMutation() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.assignRole(any(), any()))
                .thenThrow(new KeycloakUnavailableException("internal upstream detail"));
        String body = objectMapper.writeValueAsString(new AssignRoleRequest("MANAGER"));

        mockMvc.perform(post("/api/v1/users/{id}/roles", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", not(containsString("internal"))));
    }

    @Test
    void removeRoleReturns503WhenKeycloakUnavailableOnMutation() throws Exception {
        UUID id = UUID.randomUUID();
        when(keycloakRoleAdminService.removeRole(any(), any()))
                .thenThrow(new KeycloakUnavailableException("internal upstream detail"));

        mockMvc.perform(delete("/api/v1/users/{id}/roles/{roleName}", id, "MANAGER"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", not(containsString("internal"))));
    }
}
