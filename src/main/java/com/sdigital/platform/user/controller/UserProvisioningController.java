package com.sdigital.platform.user.controller;

import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.service.UserProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * ADMIN-only provisioning endpoint.
 *
 * <p>This route is intentionally separate from {@code POST /api/v1/users}: the historical
 * local-only creation endpoint remains backward-compatible and usable without a Keycloak
 * Admin configuration, while provisioning adds the remote identity and therefore has a
 * stricter failure model.</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Utilisateurs", description = "Gestion des utilisateurs de la plateforme et de leurs identités Keycloak.")
public class UserProvisioningController {

    private final UserProvisioningService userProvisioningService;

    public UserProvisioningController(UserProvisioningService userProvisioningService) {
        this.userProvisioningService = userProvisioningService;
    }

    @Operation(summary = "Provision a user with a Keycloak identity",
            description = "Creates the local user and its Keycloak identity, then links them through the "
                    + "Keycloak internal user id. The local user is created in ACTIVE status. The Keycloak "
                    + "account is enabled but receives no password, no required action and no realm role: "
                    + "credentials must be provisioned out of band. `keycloakId` is never returned. "
                    + "The response carries a `Location` header pointing to the created local user.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User and Keycloak identity created and linked"),
            @ApiResponse(responseCode = "400", description = "Invalid or unreadable request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Caller is not an ADMIN or has no linked ACTIVE local identity",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agency or function not found or inactive",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Email already used locally or Keycloak identity already exists",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Local persistence failed, Keycloak integration misconfigured, or unexpected error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "Keycloak unavailable or not configured",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/provision")
    public ResponseEntity<UserResponse> provision(@Valid @RequestBody CreateUserRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        UserResponse created = userProvisioningService.provision(request);
        URI location = uriBuilder.path("/api/v1/users/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }
}
