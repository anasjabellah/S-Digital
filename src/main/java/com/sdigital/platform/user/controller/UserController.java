package com.sdigital.platform.user.controller;

import com.sdigital.platform.user.dto.CreateUserRequest;
import com.sdigital.platform.user.dto.UpdateUserRequest;
import com.sdigital.platform.user.dto.UserResponse;
import com.sdigital.platform.user.entity.User;
import com.sdigital.platform.user.entity.UserStatus;
import com.sdigital.platform.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST API for the user module, versioned under {@code /api/v1/users}.
 *
 * <p>The controller contains no business logic: it delegates to {@link UserService}
 * and only handles HTTP concerns (status codes, {@code Location} header, binding).</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Create, read, update and deactivate platform users.")
public class UserController {

    private static final String ID_EXAMPLE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    /**
     * Sortable user properties. Anything else (in particular {@code keycloakId}, which is
     * never exposed) is rejected with 400 instead of reaching the persistence layer.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "id", "email", "firstName", "lastName", "status", "createdAt", "updatedAt");

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    private static void validateSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PropertyReferenceException(order.getProperty(), TypeInformation.of(User.class), List.of());
            }
        }
    }

    @Operation(summary = "Create a user",
            description = "Creates a new user in ACTIVE status. The response carries a "
                    + "`Location` header pointing to the created resource.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created"),
            @ApiResponse(responseCode = "400", description = "Invalid or unreadable request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Email already in use",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request,
                                               UriComponentsBuilder uriBuilder) {
        UserResponse created = userService.create(request);
        URI location = uriBuilder.path("/api/v1/users/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get a user by id", description = "Returns a single user by its UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found"),
            @ApiResponse(responseCode = "400", description = "Malformed user id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public UserResponse getById(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id) {
        return userService.getById(id);
    }

    /**
     * Returns a page of users. Pagination and sorting are bound by Spring Data
     * ({@code page}, {@code size}, {@code sort}); the maximum page size is capped
     * globally through {@code spring.data.web.pageable.max-page-size}. Optional
     * multicriteria filters ({@code search}, {@code status}) narrow the listing.
     */
    @Operation(summary = "List users",
            description = "Returns a paginated list of users. Pagination and sorting are bound by "
                    + "Spring Data: `page` (0-based, default 0) and `size` (default 20, capped at 100); "
                    + "non-numeric values fall back to their default. `sort` uses `property,asc|desc` and "
                    + "must reference an existing user property (e.g. `email`, `createdAt`); "
                    + "default `createdAt,desc`. Optional filters can be combined: `search` is a "
                    + "case-insensitive partial match on email, firstName and lastName; `status` is "
                    + "an exact match on ACTIVE or INACTIVE.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Zero-based page index.", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Page size (default 20, maximum 100).", example = "20"),
            @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Sort criteria as `property,asc|desc`; repeatable. Default `createdAt,desc`.", example = "email,asc"),
            @Parameter(name = "search", in = ParameterIn.QUERY, description = "Case-insensitive partial match on email, firstName and lastName.", example = "ali"),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "Exact lifecycle status filter: ACTIVE or INACTIVE.", example = "ACTIVE")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of users"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, sort or filter parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public Page<UserResponse> getAll(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserStatus status) {
        validateSort(pageable);
        return userService.getAll(search, status, pageable);
    }

    @Operation(summary = "Update a user",
            description = "Replaces the mutable fields (email, firstName, lastName). The user "
                    + "status is not modified by this operation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated"),
            @ApiResponse(responseCode = "400", description = "Malformed user id or invalid request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Email already in use by another user",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/{id}")
    public UserResponse update(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    /**
     * Logical deactivation: the user status becomes {@code INACTIVE}. The row is kept.
     */
    @Operation(summary = "Deactivate a user",
            description = "Logical deletion: the status becomes INACTIVE and the row is kept. "
                    + "No response body is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "User deactivated"),
            @ApiResponse(responseCode = "400", description = "Malformed user id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id) {
        userService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Activate a user",
            description = "Reactivates a deactivated user: the status becomes ACTIVE again. "
                    + "No row is created and the Keycloak identity is not modified.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User activated"),
            @ApiResponse(responseCode = "400", description = "Malformed user id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "User not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{id}/activate")
    public UserResponse activate(
            @Parameter(description = "UUID of the user", example = ID_EXAMPLE) @PathVariable UUID id) {
        return userService.activate(id);
    }
}
