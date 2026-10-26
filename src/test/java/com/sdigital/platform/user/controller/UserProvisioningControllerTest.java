package com.sdigital.platform.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.exception.EmailAlreadyUsedException;
import com.sdigital.platform.user.exception.UserAgencyNotFoundException;
import com.sdigital.platform.user.exception.UserProvisioningException;
import com.sdigital.platform.user.keycloak.exception.KeycloakIdentityAlreadyExistsException;
import com.sdigital.platform.user.keycloak.exception.KeycloakUnavailableException;
import com.sdigital.platform.user.service.UserProvisioningService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@link UserProvisioningController}. The provisioning service is mocked:
 * only status codes, headers, response shape and error sanitisation are asserted here.
 * Authentication/authorization is covered by the real filter-chain security test.
 */
@WebMvcTest(controllers = UserProvisioningController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class UserProvisioningControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserProvisioningService userProvisioningService;

    private UserResponse sample(UUID id) {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new UserResponse(id, "ada@example.com", "Ada", "Lovelace",
                UserStatus.ACTIVE, null, null, created, created);
    }

    private String body() throws Exception {
        return objectMapper.writeValueAsString(
                new CreateUserRequest("ada@example.com", "Ada", "Lovelace", null, null));
    }

    @Test
    void provisionReturns201WithBodyAndLocationAndNoKeycloakId() throws Exception {
        UUID id = UUID.randomUUID();
        when(userProvisioningService.provision(any(CreateUserRequest.class))).thenReturn(sample(id));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/users/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.keycloakId").doesNotExist());

        verify(userProvisioningService).provision(any(CreateUserRequest.class));
    }

    @Test
    void provisionReturns400WhenBodyInvalid() throws Exception {
        String invalid = objectMapper.writeValueAsString(
                new CreateUserRequest("not-an-email", "", "", null, null));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItems("email", "firstName", "lastName")));
    }

    @Test
    void provisionReturns409WhenLocalEmailAlreadyUsed() throws Exception {
        when(userProvisioningService.provision(any(CreateUserRequest.class)))
                .thenThrow(new EmailAlreadyUsedException("ada@example.com"));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Email already in use"));
    }

    @Test
    void provisionReturns409WhenKeycloakIdentityAlreadyExists() throws Exception {
        when(userProvisioningService.provision(any(CreateUserRequest.class)))
                .thenThrow(new KeycloakIdentityAlreadyExistsException());

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("A Keycloak account already exists for this email"));
    }

    @Test
    void provisionReturns404WhenAgencyIsNotFound() throws Exception {
        UUID agencyId = UUID.randomUUID();
        when(userProvisioningService.provision(any(CreateUserRequest.class)))
                .thenThrow(new UserAgencyNotFoundException(agencyId));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Agency not found"));
    }

    @Test
    void provisionReturnsGeneric500WithoutLeakingDetails() throws Exception {
        when(userProvisioningService.provision(any(CreateUserRequest.class)))
                .thenThrow(new UserProvisioningException(
                        UserProvisioningException.Reason.LOCAL_PERSISTENCE_FAILED,
                        new RuntimeException("sensitive internal detail")));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("sensitive"))))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("ada@example.com"))))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("kc-internal"))));
    }

    @Test
    void provisionReturns503WithoutLeakingKeycloakDetails() throws Exception {
        when(userProvisioningService.provision(any(CreateUserRequest.class)))
                .thenThrow(new KeycloakUnavailableException("upstream detail", new RuntimeException("secret")));

        mockMvc.perform(post("/api/v1/users/provision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Keycloak is temporarily unavailable"))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("upstream"))))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("secret"))));
    }
}
