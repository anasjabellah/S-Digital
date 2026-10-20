package com.sdigital.platform.user.dto;

import com.sdigital.platform.function.entity.FunctionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Minimal function representation embedded in {@link UserResponse}.
 */
@Schema(name = "UserFunctionDto", description = "Minimal function representation embedded in a user response.")
public record UserFunctionDto(
        @Schema(description = "Function identifier.", example = "5fa85f64-5717-4562-b3fc-2c963f66afa7")
        UUID id,
        @Schema(description = "Function code.", example = "F-INGE-01")
        String code,
        @Schema(description = "Function name.", example = "Engineer 01")
        String name,
        @Schema(description = "Function status.", example = "ACTIVE")
        FunctionStatus status
) {
}
