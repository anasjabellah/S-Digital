package com.sdigital.platform.email.dto;

import com.sdigital.platform.email.entity.EmailTemplateStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model returned to clients for an email template.
 */
@Schema(name = "EmailTemplateResponse", description = "Email template representation returned by the API.")
public record EmailTemplateResponse(

        @Schema(description = "Server-generated identifier.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Unique template key.", example = "USER_WELCOME")
        String code,

        @Schema(description = "Display name of the template.", example = "Welcome email")
        String name,

        @Schema(description = "Email subject.", example = "Welcome {{firstName}}")
        String subject,

        @Schema(description = "Email body.", example = "Hello {{firstName}}, your account is ready.")
        String body,

        @Schema(description = "Language tag of the template content.", example = "fr")
        String locale,

        @Schema(description = "Declared placeholder names.", example = "[\"firstName\"]")
        List<String> variables,

        @Schema(description = "Template status. `ACTIVE` for a usable template, `INACTIVE` after logical deactivation.",
                example = "ACTIVE")
        EmailTemplateStatus status,

        @Schema(description = "Creation timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant createdAt,

        @Schema(description = "Last modification timestamp (UTC).", example = "2026-01-01T12:00:00Z")
        Instant updatedAt
) {
}
