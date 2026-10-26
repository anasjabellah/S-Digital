package com.sdigital.platform.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request payload to create a new email template.
 *
 * <p>Technical fields ({@code id}, {@code status}, timestamps) are intentionally absent:
 * they are owned by the platform. Placeholders in {@code subject} and {@code body} use
 * the {@code {{variableName}}} form and must be declared in {@code variables}.</p>
 */
@Schema(name = "CreateEmailTemplateRequest", description = "Payload to create a new email template.")
public record CreateEmailTemplateRequest(

        @Schema(description = "Unique template key.", example = "USER_WELCOME")
        @NotBlank(message = "code is required")
        @Size(max = 100, message = "code must not exceed 100 characters")
        String code,

        @Schema(description = "Display name of the template.", example = "Welcome email")
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must not exceed 150 characters")
        String name,

        @Schema(description = "Email subject. May contain {{variable}} placeholders.",
                example = "Welcome {{firstName}}")
        @NotBlank(message = "subject is required")
        @Size(max = 255, message = "subject must not exceed 255 characters")
        String subject,

        @Schema(description = "Email body. May contain {{variable}} placeholders.",
                example = "Hello {{firstName}}, your account is ready.")
        @NotBlank(message = "body is required")
        @Size(max = 20000, message = "body must not exceed 20000 characters")
        String body,

        @Schema(description = "BCP 47 language tag of the template content.", example = "fr")
        @NotBlank(message = "locale is required")
        @Size(max = 10, message = "locale must not exceed 10 characters")
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$", message = "locale must be a language tag such as fr or fr-FR")
        String locale,

        @Schema(description = "Declared placeholder names that may appear in subject and body.",
                example = "[\"firstName\"]")
        @Valid
        List<@NotBlank(message = "variable name must not be blank")
                @Size(max = 64, message = "variable name must not exceed 64 characters")
                @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$",
                        message = "variable name must be an identifier") String> variables
) {
}
