package com.sdigital.platform.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read model of a single in-app notification.
 */
@Schema(name = "NotificationResponse", description = "A single in-app notification addressed to a user.")
public record NotificationResponse(

        @Schema(description = "Server-generated identifier of the notification.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Identifier of the user the notification is addressed to.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID userId,

        @Schema(description = "Notification title.", example = "Nouvel utilisateur créé")
        String title,

        @Schema(description = "Notification message.", example = "L'utilisateur alice@example.com a été créé.")
        String message,

        @Schema(description = "Notification type (e.g. INFO, WARNING, ASSIGNMENT).", example = "INFO")
        String type,

        @Schema(description = "Whether the notification has been read.", example = "false")
        boolean read,

        @Schema(description = "Moment the notification was created (UTC).", example = "2026-01-10T10:00:00Z")
        Instant createdAt,

        @Schema(description = "Moment the notification was marked as read (UTC), null if unread.", example = "2026-01-10T10:05:00Z")
        Instant readAt,

        @Schema(description = "Optional link the notification points to.", example = "/api/v1/users/3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String link,

        @Schema(description = "Structured metadata. Never contains tokens, secrets or personal data.")
        Map<String, Object> metadata
) {
}
