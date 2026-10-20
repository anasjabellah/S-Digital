package com.sdigital.platform.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.agency.controller.AgencyController;
import com.sdigital.platform.agency.dto.AgencyResponse;
import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.service.AgencyService;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.function.controller.FunctionController;
import com.sdigital.platform.function.dto.CreateFunctionRequest;
import com.sdigital.platform.function.dto.FunctionResponse;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.service.FunctionService;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.repository.UserRepository;
import com.sdigital.platform.user.service.UserIdentityService;
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
 * Asserts the RBAC policy applied to the reference data (agencies and functions) through the
 * real filter chain: bearer JWT -> {@code JwtDecoder} -> {@link KeycloakRolesConverter} ->
 * {@link LocalUserActiveGuardFilter} -> {@code AuthorizationFilter}.
 *
 * <p>Policy under test: reads on {@code /api/v1/agencies} and {@code /api/v1/functions} require
 * {@code ROLE_ADMIN} or {@code ROLE_MANAGER}; create, update, deactivate and activate require
 * {@code ROLE_ADMIN}. Denials return the same generic 403 JSON as elsewhere, and a missing
 * identity still wins over a valid role.</p>
 */
@WebMvcTest(controllers = {AgencyController.class, FunctionController.class})
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class ReferenceDataAuthorizationSecurityTest {

    /** {@code sub} claim signed by the test tokens, therefore the linked local keycloakId. */
    private static final String KEYCLOAK_SUB = "test-user";
    private static final UUID AGENCY_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    private static final UUID FUNCTION_ID = UUID.fromString("5fa85f64-5717-4562-b3fc-2c963f66afa7");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AgencyService agencyService;

    @MockitoBean
    private FunctionService functionService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void linkedActiveLocalUser() {
        User user = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        user.setKeycloakId(KEYCLOAK_SUB);
        user.setStatus(UserStatus.ACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(user));
    }

    // --- ADMIN: reads and writes allowed ------------------------------------------------------

    @Test
    void adminCanReadAndWriteAgencies() throws Exception {
        when(agencyService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(agencyService.getById(AGENCY_ID)).thenReturn(agencyResponse());
        when(agencyService.create(any(CreateAgencyRequest.class))).thenReturn(agencyResponse());
        when(agencyService.activate(AGENCY_ID)).thenReturn(agencyResponse());
        String body = objectMapper.writeValueAsString(new CreateAgencyRequest("AG-PARIS-01", "Agency Paris 01"));

        mockMvc.perform(get("/api/v1/agencies").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/agencies/" + AGENCY_ID).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/agencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/agencies/" + AGENCY_ID)));
        mockMvc.perform(put("/api/v1/agencies/" + AGENCY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/agencies/" + AGENCY_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/agencies/" + AGENCY_ID + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanReadAndWriteFunctions() throws Exception {
        when(functionService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(functionService.getById(FUNCTION_ID)).thenReturn(functionResponse());
        when(functionService.create(any(CreateFunctionRequest.class))).thenReturn(functionResponse());
        when(functionService.activate(FUNCTION_ID)).thenReturn(functionResponse());
        String body = objectMapper.writeValueAsString(new CreateFunctionRequest("F-INGE-01", "Engineer 01"));

        mockMvc.perform(get("/api/v1/functions").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/functions/" + FUNCTION_ID).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/functions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/functions/" + FUNCTION_ID)));
        mockMvc.perform(put("/api/v1/functions/" + FUNCTION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/functions/" + FUNCTION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/functions/" + FUNCTION_ID + "/activate")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    // --- MANAGER: reads allowed, writes refused ----------------------------------------------

    @Test
    void managerCanReadAgenciesAndFunctions() throws Exception {
        when(agencyService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(agencyService.getById(AGENCY_ID)).thenReturn(agencyResponse());
        when(functionService.getAll(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(functionService.getById(FUNCTION_ID)).thenReturn(functionResponse());

        mockMvc.perform(get("/api/v1/agencies").header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/agencies/" + AGENCY_ID).header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/functions").header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/functions/" + FUNCTION_ID).header(HttpHeaders.AUTHORIZATION, bearer("MANAGER")))
                .andExpect(status().isOk());
    }

    @Test
    void managerCannotWriteAgencies() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateAgencyRequest("AG-PARIS-01", "Agency Paris 01"));

        expectForbiddenJson(mockMvc.perform(post("/api/v1/agencies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(put("/api/v1/agencies/" + AGENCY_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(delete("/api/v1/agencies/" + AGENCY_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/agencies/" + AGENCY_ID + "/activate")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));

        verifyNoInteractions(agencyService);
    }

    @Test
    void managerCannotWriteFunctions() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateFunctionRequest("F-INGE-01", "Engineer 01"));

        expectForbiddenJson(mockMvc.perform(post("/api/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(put("/api/v1/functions/" + FUNCTION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(delete("/api/v1/functions/" + FUNCTION_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/functions/" + FUNCTION_ID + "/activate")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));

        verifyNoInteractions(functionService);
    }

    // --- USER: no reference-data access at all -------------------------------------------------

    @Test
    void userRoleIsDeniedAllReferenceDataAccess() throws Exception {
        String agencyBody = objectMapper.writeValueAsString(
                new CreateAgencyRequest("AG-PARIS-01", "Agency Paris 01"));
        String functionBody = objectMapper.writeValueAsString(
                new CreateFunctionRequest("F-INGE-01", "Engineer 01"));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/agencies")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(get("/api/v1/functions")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/agencies")
                .contentType(MediaType.APPLICATION_JSON).content(agencyBody)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        expectForbiddenJson(mockMvc.perform(post("/api/v1/functions")
                .contentType(MediaType.APPLICATION_JSON).content(functionBody)
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));

        verifyNoInteractions(agencyService, functionService);
    }

    // --- No implicit access from scopes or client roles ---------------------------------------

    @Test
    void tokenWithoutBusinessRolesIsDenied() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("scope", "profile email"));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/agencies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
        expectForbiddenJson(mockMvc.perform(get("/api/v1/functions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));

        verifyNoInteractions(agencyService, functionService);
    }

    @Test
    void trustedClientRoleDoesNotSatisfyBusinessRole() throws Exception {
        String token = KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.signedToken(
                Map.of("resource_access", Map.of("s-digital-api", Map.of("roles", List.of("ADMIN")))));

        mockMvc.perform(get("/api/v1/agencies").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        verifyNoInteractions(agencyService);
    }

    // --- A role never bypasses the identity guard ----------------------------------------------

    @Test
    void adminTokenWithoutLinkedLocalUserIsDeniedByTheGuard() throws Exception {
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());

        expectForbiddenJson(mockMvc.perform(get("/api/v1/agencies")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(agencyService);
    }

    @Test
    void adminTokenWithInactiveLocalUserIsDeniedByTheGuard() throws Exception {
        User inactive = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        inactive.setKeycloakId(KEYCLOAK_SUB);
        inactive.setStatus(UserStatus.INACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(inactive));

        expectForbiddenJson(mockMvc.perform(get("/api/v1/functions")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))))
                .andExpect(content().string(not(containsString(KEYCLOAK_SUB))));

        verifyNoInteractions(functionService);
    }

    // --- Authentication failures stay 401 ------------------------------------------------------

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/agencies")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/functions")).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/agencies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
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

    private AgencyResponse agencyResponse() {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new AgencyResponse(AGENCY_ID, "AG-PARIS-01", "Agency Paris 01", AgencyStatus.ACTIVE, created, created);
    }

    private FunctionResponse functionResponse() {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new FunctionResponse(FUNCTION_ID, "F-INGE-01", "Engineer 01", FunctionStatus.ACTIVE, created, created);
    }
}
