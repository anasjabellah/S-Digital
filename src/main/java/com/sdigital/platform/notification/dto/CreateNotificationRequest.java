package com.sdigital.platform.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Request payload used by an administrator to emit an in-app notification.
 *
 * <p>The notification is always created unread and addressed to a single existing local
 * user. The recipient then owns it: only that user can read it or mark it as read.
 * Metadata is optional and must never contain tokens, secrets or credentials.</p>
 */
@Schema(name = "CreateNotificationRequest", description = "Payload to emit an in-app notification to a local user.")
public record CreateNotificationRequest(

        @Schema(description = "Identifier of the local user the notification is addressed to.",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @NotNull(message = "recipientUserId is required")
        UUID recipientUserId,

        @Schema(description = "Notification title.", example = "Nouvelle affectation")
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must not exceed 200 characters")
        String title,

        @Schema(description = "Notification message.", example = "Vous avez été affecté à l'agence AG-PARIS-01.")
        @NotBlank(message = "message is required")
        @Size(max = 1000, message = "message must not exceed 1000 characters")
        String message,

        @Schema(description = "Notification type (e.g. INFO, WARNING, ASSIGNMENT).", example = "INFO")
        @NotBlank(message = "type is required")
        @Size(max = 50, message = "type must not exceed 50 characters")
        String type,

        @Schema(description = "Optional link the notification points to.",
                example = "/api/v1/users/3fa85f64-5717-4562-b3fc-2c963f66afa6")
        @Size(max = 500, message = "link must not exceed 500 characters")
        String link,

        @Schema(description = "Optional structured metadata. Never contains tokens, secrets or personal data.")
        Map<String, Object> metadata
) {
}
