package com.sdigital.platform.notification.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.notification.dto.NotificationResponse;
import com.sdigital.platform.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
}
