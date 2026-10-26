package com.sdigital.platform.common.security;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.notification.controller.NotificationController;
import com.sdigital.platform.notification.dto.CreateNotificationRequest;
import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.service.NotificationService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts that emitting a notification is ADMIN-only through the real security filter chain,
 * while reading and marking notifications as read remain available to any authenticated user.
 */
@WebMvcTest(controllers = NotificationController.class)
@Import({SecurityConfig.class, KeycloakJwtAuthenticationConverterConfig.class,
        GlobalExceptionHandler.class, UserIdentityService.class, CurrentActorResolver.class,
        KeycloakRolesSecurityIntegrationTest.TestJwtConfiguration.class})
@TestPropertySource(properties = {
        "sdigital.security.cors.allowed-origins=https://app.example.com",
        "sdigital.security.keycloak.client-role-ids=s-digital-api"
})
class NotificationAuthorizationSecurityTest {

    private static final String KEYCLOAK_SUB = "test-user";
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NOTIFICATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private UserRepository userRepository;

    @BeforeEach
    void linkedActiveLocalUser() {
        User user = new User("ada.lovelace@example.com", "Ada", "Lovelace");
        user.setKeycloakId(KEYCLOAK_SUB);
        user.setStatus(UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", ACTOR_ID);
        lenient().when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.of(user));

        NotificationResponse response = new NotificationResponse(
                NOTIFICATION_ID, ACTOR_ID, "Titre", "Message", "INFO", false,
                Instant.parse("2026-01-01T10:00:00Z"), null, null, Map.of());
        lenient().when(notificationService.createForRecipient(any(CreateNotificationRequest.class),
                eq(ACTOR_ID))).thenReturn(response);
        lenient().when(notificationService.getNotification(eq(NOTIFICATION_ID), eq(ACTOR_ID)))
                .thenReturn(response);
        lenient().when(notificationService.markAllAsRead(ACTOR_ID)).thenReturn(0);
    }

    @Test
    void adminCanCreateNotification() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()));
    }

    @Test
    void managerCannotCreateNotification() throws Exception {
        expectForbidden(mockMvc.perform(post("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, bearer("MANAGER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())));
        verifyNoInteractions(notificationService);
    }

    @Test
    void userCannotCreateNotification() throws Exception {
        expectForbidden(mockMvc.perform(post("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())));
        verifyNoInteractions(notificationService);
    }

    @Test
    void missingJwtReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(notificationService);
    }

    @Test
    void adminTokenIsDeniedWhenLocalIdentityIsNotLinked() throws Exception {
        when(userRepository.findByKeycloakId(KEYCLOAK_SUB)).thenReturn(Optional.empty());

        expectForbidden(mockMvc.perform(post("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBody())));
        verifyNoInteractions(notificationService);
    }

    @Test
    void authenticatedUserCanReadOwnNotification() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/{id}", NOTIFICATION_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()));
    }

    @Test
    void adminOnlyPostRuleDoesNotBlockMarkAllAsRead() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/read/all")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string("0"));
    }

    private static String validBody() {
        return """
                {"recipientUserId":"%s","title":"Titre","message":"Message","type":"INFO"}
                """.formatted(ACTOR_ID);
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
