package com.sdigital.platform.function.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to create a new function.
 *
 * <p>Technical fields ({@code id}, {@code status}, timestamps) are intentionally absent:
 * they are owned by the platform.</p>
 */
@Schema(name = "CreateFunctionRequest", description = "Payload to create a new function.")
public record CreateFunctionRequest(

        @Schema(description = "Unique code of the function.", example = "F-INGE")
        @NotBlank(message = "code is required")
        @Size(max = 50, message = "code must not exceed 50 characters")
        String code,

        @Schema(description = "Display name of the function.", example = "Engineer")
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must not exceed 150 characters")
        String name
) {
}
