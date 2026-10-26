package com.sdigital.platform.common.security;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.email.controller.EmailTemplateController;
import com.sdigital.platform.email.dto.EmailTemplateResponse;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import com.sdigital.platform.email.service.EmailTemplateService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts that email-template routes are ADMIN-only through the real security filter chain.
 */
@WebMvcTest(controllers = EmailTemplateController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class EmailTemplateAuthorizationSecurityTest {

    private static final String KEYCLOAK_SUB = "test-user";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmailTemplateService emailTemplateService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void linkedActiveLocalUser() {
        User user = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        user.setKeycloakId(KEYCLOAK_SUB);
        user.setStatus(UserStatus.ACTIVE);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(user));
        lenient().when(emailTemplateService.getAll(any(), any(), any(), any())).thenReturn(Page.empty());
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        lenient().when(emailTemplateService.getById(any())).thenReturn(new EmailTemplateResponse(
                UUID.randomUUID(), "USER_WELCOME", "Welcome email", "S", "B", "fr",
                List.of(), EmailTemplateStatus.ACTIVE, now, now));
    }

    @Test
    void adminCanListTemplates() throws Exception {
        mockMvc.perform(get("/api/v1/email-templates").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void managerIsDenied() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/email-templates")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))));
        verifyNoInteractions(emailTemplateService);
    }

    @Test
    void userIsDenied() throws Exception {
        expectForbidden(mockMvc.perform(get("/api/v1/email-templates")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))));
        verifyNoInteractions(emailTemplateService);
    }

    @Test
    void managerCannotCreate() throws Exception {
        expectForbidden(mockMvc.perform(post("/api/v1/email-templates")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code":"USER_WELCOME","name":"Welcome","subject":"S","body":"B","locale":"fr","variables":[]}
                        """)));
        verifyNoInteractions(emailTemplateService);
    }

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/email-templates")).andExpect(status().isUnauthorized());
        verifyNoInteractions(emailTemplateService);
    }

    @Test
    void adminTokenIsDeniedWhenLocalIdentityIsNotLinked() throws Exception {
        when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());

        expectForbidden(mockMvc.perform(get("/api/v1/email-templates")
                .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))));
        verifyNoInteractions(emailTemplateService);
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
