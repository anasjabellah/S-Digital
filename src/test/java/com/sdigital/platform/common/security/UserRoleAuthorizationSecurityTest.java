package com.sdigital.platform.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.controller.UserController;
import com.sdigital.platform.user.controller.UserProvisioningController;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.service.UserIdentityService;
import com.sdigital.platform.user.service.UserProvisioningService;
import com.sdigital.platform.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the RBAC policy of {@link SecurityConfig} through the real filter chain:
 * bearer JWT -> {@code JwtDecoder} -> {@link KeycloakRolesConverter} ->
 * {@link LocalUserActiveGuardFilter} -> the authorization filter ({@code AuthorizationFilter}).
 *
 * <p>{@link UserIdentityService} is the real implementation (only {@link UserRepository} and
 * {@link UserService} are mocked), so the "linked + ACTIVE" check runs against the actual local
 * identity code while the JWT sub is the one signed by the test keys of
 * {@link KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration}.</p>
 *
 * <p>Policy under test: reads on {@code /api/v1/users[/{id}]} require {@code ROLE_ADMIN} or
 * {@code ROLE_MANAGER}; create, update and deactivate require {@code ROLE_ADMIN}. A role never
 * compensates for a missing or INACTIVE local identity, and neither client roles nor OAuth2
 * scopes satisfy a business role.</p>
 */
@WebMvcTest(controllers = {UserController.class, UserProvisioningController.class})
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class UserRoleAuthorizationSecurityTest {

    /** {@code sub} claim signed by the test tokens, therefore the linked local keycloakId. */
    private static final String KEYCLOAK_SUB = "test-user";
    private static final UUID USER_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private UserProvisioningService userProvisioningService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void linkedActiveLocalUser() {
        User user = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        user.setKeycloakId(KEYCLOAK_SUB);
        user.setStatus(UserStatus.ACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(user));
    }

    // --- ADMIN: full user management ----------------------------------------------------------

    @Test
    void adminCanListUsers() throws Exception {
        when(userService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanGetUserById() throws Exception {
        when(userService.getById(USER_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/users/" + USER_ID).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));
    }

    @Test
    void adminCanCreateUser() throws Exception {
        when(userService.create(any(CreateUserRequest.class))).thenReturn(sampleResponse());
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/users/" + USER_ID)));
    }

    @Test
    void adminCanProvisionUser() throws Exception {
        when(userProvisioningService.provision(any(CreateUserRequest.class))).thenReturn(sampleResponse());
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/users/" + USER_ID)));

        verify(userProvisioningService).provision(any(CreateUserRequest.class));
    }

    @Test
    void adminCanUpdateUser() throws Exception {
        when(userService.update(eq(USER_ID), any(UpdateUserRequest.class))).thenReturn(sampleResponse());
        String body = objectMapper.writeValueAsString(
                new UpdateUserRequest("ada.lovelace@example.com", "Ada", "Lovelace", null, null));

        mockMvc.perform(put("/api/v1/users/" + USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()));
    }

    @Test
    void adminCanDeactivateUser() throws Exception {
        mockMvc.perform(delete("/api/v1/users/" + USER_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isNoContent());

        verify(userService).deactivate(USER_ID);
    }

    @Test
    void adminCanActivateUser() throws Exception {
        when(userService.activate(USER_ID)).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/users/" + USER_ID + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // --- MANAGER: reads allowed, writes refused ------------------------------------------------

    @Test
    void managerCanReadUsers() throws Exception {
        when(userService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(userService.getById(USER_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/" + USER_ID).header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void managerCannotCreateUpdateDeactivateOrActivateUsers() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        expectForbiddenJson(mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(put("/api/v1/users/" + USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(delete("/api/v1/users/" + USER_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/users/" + USER_ID + "/activate")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/users/provision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));

        verifyNoInteractions(userService);
        verifyNoInteractions(userProvisioningService);
    }

    // --- USER: no user-management access at all ------------------------------------------------

    @Test
    void userRoleIsDeniedReads() throws Exception {
        expectForbiddenJson(mockMvc.perform(get("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(get("/api/v1/users/" + USER_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));

        verifyNoInteractions(userService);
    }

    @Test
    void userRoleIsDeniedWrites() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        expectForbiddenJson(mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(put("/api/v1/users/" + USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(delete("/api/v1/users/" + USER_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/users/" + USER_ID + "/activate")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/users/provision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));

        verifyNoInteractions(userService);
        verifyNoInteractions(userProvisioningService);
    }

    // --- No implicit access from scopes or client roles ----------------------------------------

    @Test
    void tokenWithoutBusinessRolesIsDenied() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("scope", "profile email"));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));

        verifyNoInteractions(userService);
    }

    @Test
    void trustedClientRoleDoesNotSatisfyBusinessRole() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("resource_access", Map.of("s-digital-api", Map.of("roles", List.of("ADMIN")))));

        mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userService);
    }

    // --- A role never bypasses the identity guard ----------------------------------------------

    @Test
    void adminTokenWithoutLinkedLocalUserIsDeniedByTheGuard() throws Exception {
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());

        expectForbiddenJson(mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(userService);
    }

    @Test
    void adminTokenWithInactiveLocalUserIsDeniedByTheGuard() throws Exception {
        User inactive = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        inactive.setKeycloakId(KEYCLOAK_SUB);
        inactive.setStatus(UserStatus.INACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(inactive));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(userService);
    }

    @Test
    void adminTokenWithoutLinkedLocalUserCannotProvision() throws Exception {
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        expectForbiddenJson(mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(userProvisioningService);
    }

    @Test
    void adminTokenWithInactiveLocalUserCannotProvision() throws Exception {
        User inactive = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        inactive.setKeycloakId(KEYCLOAK_SUB);
        inactive.setStatus(UserStatus.INACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(inactive));
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        expectForbiddenJson(mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))));

        verifyNoInteractions(userProvisioningService);
    }

    /**
     * A role denial (guard passed, role missing) and an identity denial (ADMIN role present,
     * identity unlinked) must be indistinguishable apart from being 403: same media type and
     * exact same generic body, with no role, subject or exception detail.
     */
    @Test
    void roleDenialAndGuardDenialCarryTheSameGenericJson() throws Exception {
        String createBody = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));
        MvcResult roleDenial = expectForbiddenJson(mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))).andReturn();

        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());
        MvcResult guardDenial = expectForbiddenJson(mockMvc.perform(get("/api/v1/users")
                .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))).andReturn();

        assertThat(roleDenial.getResponse().getContentType())
                .isEqualTo(guardDenial.getResponse().getContentType());
        assertThat(roleDenial.getResponse().getContentAsString())
                .isEqualTo(guardDenial.getResponse().getContentAsString())
                .doesNotContain(KEYCLOAK_SUB, "ADMIN", "MANAGER", "USER", "ROLE_", "Bearer");
    }

    // --- Authentication failures stay 401 ------------------------------------------------------

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingJwtCannotProvision() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateUserRequest("bob@example.com", "Bob", "Marley", null, null));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userProvisioningService);
    }

    // --- Helpers -------------------------------------------------------------------------------

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

    private static String bearer(String realmRole) {
        return "Bearer " + KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("realm_access", Map.of("roles", List.of(realmRole))));
    }

    private static String expiredToken() {
        return KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of(), Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));
    }

    private UserResponse sampleResponse() {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new UserResponse(USER_ID, "ada.lovelace@example.com", "Ada", "Lovelace",
                UserStatus.ACTIVE, null, null, created, created);
    }
}

