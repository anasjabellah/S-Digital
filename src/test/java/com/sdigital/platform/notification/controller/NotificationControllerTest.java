package com.sdigital.platform.notification.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.notification.dto.CreateNotificationRequest;
import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.exception.NotificationNotFoundException;
import com.sdigital.platform.notification.service.NotificationService;
import com.sdigital.platform.user.exception.UserNotFoundException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID NOTIFICATION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private CurrentActorResolver currentActorResolver;

    @Test
    void listNotificationsReturnsCurrentUserPage() throws Exception {
        NotificationResponse n = new NotificationResponse(
                NOTIFICATION_ID, USER_ID, "Titre", "Message", "INFO", false,
                Instant.parse("2026-01-10T10:00:00Z"), null, "/users/1", Map.of());
        Page<NotificationResponse> page = new PageImpl<>(List.of(n), PageRequest.of(0, 20), 1);
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.getNotifications(eq(USER_ID), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.content[0].id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.content[0].title").value("Titre"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listUnreadNotifications() throws Exception {
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.getUnreadNotifications(eq(USER_ID), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/notifications/unread"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void countUnreadReturnsNumber() throws Exception {
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.countUnread(USER_ID)).thenReturn(3L);

        mockMvc.perform(get("/api/v1/notifications/unread/count"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(content().string("3"));
    }

    @Test
    void markAsReadReturnsNoContent() throws Exception {
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        doNothing().when(notificationService).markAsRead(NOTIFICATION_ID, USER_ID);

        mockMvc.perform(patch("/api/v1/notifications/{id}/read", NOTIFICATION_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void markAllAsReadReturnsCount() throws Exception {
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.markAllAsRead(USER_ID)).thenReturn(2);

        mockMvc.perform(post("/api/v1/notifications/read/all"))
                .andExpect(status().isOk())
                .andExpect(content().string("2"));
    }

    @Test
    void getByIdReturns200WhenNotificationIsOwned() throws Exception {
        NotificationResponse n = new NotificationResponse(
                NOTIFICATION_ID, USER_ID, "Titre", "Message", "INFO", false,
                Instant.parse("2026-01-10T10:00:00Z"), null, "/users/1", Map.of());
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.getNotification(NOTIFICATION_ID, USER_ID)).thenReturn(n);

        mockMvc.perform(get("/api/v1/notifications/{id}", NOTIFICATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.read").value(false));
    }

    @Test
    void getByIdReturns404WhenUnknownOrNotOwned() throws Exception {
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.getNotification(NOTIFICATION_ID, USER_ID))
                .thenThrow(new NotificationNotFoundException(NOTIFICATION_ID));

        mockMvc.perform(get("/api/v1/notifications/{id}", NOTIFICATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Notification not found"));
    }

    @Test
    void createReturns201WithBodyAndLocation() throws Exception {
        NotificationResponse created = new NotificationResponse(
                NOTIFICATION_ID, USER_ID, "Titre", "Message", "INFO", false,
                Instant.parse("2026-01-10T10:00:00Z"), null, "/users/1", Map.of());
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.createForRecipient(any(CreateNotificationRequest.class), eq(USER_ID)))
                .thenReturn(created);
        String body = """
                {"recipientUserId":"%s","title":"Titre","message":"Message","type":"INFO","link":"/users/1"}
                """.formatted(USER_ID);

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location",
                        Matchers.endsWith("/api/v1/notifications/" + NOTIFICATION_ID)))
                .andExpect(jsonPath("$.id").value(NOTIFICATION_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()));

        ArgumentCaptor<CreateNotificationRequest> captor =
                ArgumentCaptor.forClass(CreateNotificationRequest.class);
        verify(notificationService).createForRecipient(captor.capture(), eq(USER_ID));
        assertThat(captor.getValue().recipientUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().title()).isEqualTo("Titre");
    }

    @Test
    void createReturns400WhenBodyInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"message\":\"\",\"type\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[*].field",
                        Matchers.hasItems("recipientUserId", "title", "message", "type")));
    }

    @Test
    void createReturns404WhenRecipientDoesNotExist() throws Exception {
        UUID unknownId = UUID.randomUUID();
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(USER_ID);
        when(notificationService.createForRecipient(any(CreateNotificationRequest.class), eq(USER_ID)))
                .thenThrow(new UserNotFoundException(unknownId));
        String body = """
                {"recipientUserId":"%s","title":"Titre","message":"Message","type":"INFO"}
                """.formatted(unknownId);

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("User not found"));
    }
}
