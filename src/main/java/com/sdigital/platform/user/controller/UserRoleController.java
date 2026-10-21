package com.sdigital.platform.user.controller;

import com.sdigital.platform.user.dto.AssignRoleRequest;
import com.sdigital.platform.user.dto.UserRolesResponse;
import com.sdigital.platform.user.keycloak.KeycloakRoleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST API for managing a user's Keycloak realm roles, under
 * {@code /api/v1/users/{id}/roles}.
 *
 * <p>Access is restricted to {@code ADMIN} at the security layer (see
 * {@code SecurityConfig}); the controller only handles HTTP concerns and delegates
 * every business rule to {@link KeycloakRoleAdminService}.</p>
 *
 * <p>Only realm roles within the configured allowlist are exposed or manageable;
 * client roles are never touched.</p>
 */
@RestController
@RequestMapping("/api/v1/users/{id}/roles")
@Tag(name = "User roles", description = "Consult and manage a user's Keycloak realm roles (ADMIN only).")
public class UserRoleController {

    private static final String ID_EXAMPLE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private final KeycloakRoleAdminService keycloakRoleAdminService;

    public UserRoleController(KeycloakRoleAdminService keycloakRoleAdminService) {
        this.keycloakRoleAdminService = keycloakRoleAdminService;
    }

    @Operation(summary = "List a user's realm roles",
            description = "Returns the manageable realm roles assigned to the user. Roles outside the "
                    + "allowlist (including ADMIN) are never returned by name; their presence is only "
                    + "signalled through the 'hasUnmanageableRoles' flag.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles returned"),
            @ApiResponse(responseCode = "400", description = "Malformed user id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found (local or Keycloak)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Local user not linked to Keycloak",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Keycloak unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public UserRolesResponse getRoles(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id) {
        return keycloakRoleAdminService.getRoles(id);
    }

    @Operation(summary = "Assign a realm role",
            description = "Assigns a single allowlisted realm role to the user. ADMIN cannot be assigned "
                    + "and an administrator cannot modify their own roles. The operation is idempotent: "
                    + "assigning an already-assigned role succeeds (200) without side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role assigned; returns the updated roles"),
            @ApiResponse(responseCode = "400", description = "Invalid role name",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Role change not allowed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found (local or Keycloak)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Local user not linked to Keycloak",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Keycloak unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public UserRolesResponse assignRole(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id,
            @Valid @RequestBody AssignRoleRequest request) {
        return keycloakRoleAdminService.assignRole(id, request.roleName());
    }

    @Operation(summary = "Remove a realm role",
            description = "Removes a single allowlisted realm role from the user. ADMIN cannot be removed "
                    + "and an administrator cannot modify their own roles. The operation is idempotent: "
                    + "removing an unassigned role succeeds (200) without side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role removed; returns the updated roles"),
            @ApiResponse(responseCode = "400", description = "Invalid role name",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Role change not allowed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found (local or Keycloak)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Local user not linked to Keycloak",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Keycloak unavailable",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{roleName}")
    public UserRolesResponse removeRole(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id,
            @Parameter(description = "Realm role name to remove", example = "MANAGER") @PathVariable String roleName) {
        return keycloakRoleAdminService.removeRole(id, roleName);
    }
}
