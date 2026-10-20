package com.sdigital.platform.function.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload to update an existing function (full replacement of mutable fields).
 */
@Schema(name = "UpdateFunctionRequest", description = "Payload to update an existing function (full replacement of mutable fields).")
public record UpdateFunctionRequest(

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
