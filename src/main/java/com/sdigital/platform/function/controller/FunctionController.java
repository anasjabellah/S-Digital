package com.sdigital.platform.function.controller;

import com.sdigital.platform.function.dto.CreateFunctionRequest;
import com.sdigital.platform.function.dto.FunctionResponse;
import com.sdigital.platform.function.dto.UpdateFunctionRequest;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.service.FunctionService;
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
 * REST API for the function module, versioned under {@code /api/v1/functions}.
 *
 * <p>The controller contains no business logic: it delegates to {@link FunctionService}
 * and only handles HTTP concerns (status codes, {@code Location} header, binding).</p>
 */
@RestController
@RequestMapping("/api/v1/functions")
@Tag(name = "Référentiels", description = "Référentiels de la plateforme (fonctions métiers).")
public class FunctionController {

    private static final String ID_EXAMPLE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    /**
     * Sortable function properties. Anything else (in particular technical fields) is
     * rejected with 400 instead of reaching the persistence layer.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "id", "code", "name", "status", "createdAt", "updatedAt");

    private final FunctionService functionService;

    public FunctionController(FunctionService functionService) {
        this.functionService = functionService;
    }

    private static void validateSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PropertyReferenceException(order.getProperty(), TypeInformation.of(Function.class), List.of());
            }
        }
    }

    @Operation(summary = "Create a function",
            description = "Creates a new function in ACTIVE status. The response carries a "
                    + "`Location` header pointing to the created resource.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Function created"),
            @ApiResponse(responseCode = "400", description = "Invalid or unreadable request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Function code already in use",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<FunctionResponse> create(@Valid @RequestBody CreateFunctionRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        FunctionResponse created = functionService.create(request);
        URI location = uriBuilder.path("/api/v1/functions/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get a function by id", description = "Returns a single function by its UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Function found"),
            @ApiResponse(responseCode = "400", description = "Malformed function id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Function not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public FunctionResponse getById(
            @Parameter(description = "UUID of the function", example = ID_EXAMPLE) @PathVariable UUID id) {
        return functionService.getById(id);
    }

    /**
     * Returns a page of functions. Pagination and sorting are bound by Spring Data
     * ({@code page}, {@code size}, {@code sort}); the maximum page size is capped
     * globally through {@code spring.data.web.pageable.max-page-size}. Optional
     * multicriteria filters ({@code search}, {@code status}) narrow the listing.
     */
    @Operation(summary = "List functions",
            description = "Returns a paginated list of functions. Pagination and sorting are bound by "
                    + "Spring Data: `page` (0-based, default 0) and `size` (default 20, capped at 100); "
                    + "non-numeric values fall back to their default. `sort` uses `property,asc|desc` and "
                    + "must reference an existing function property (e.g. `code`, `createdAt`); "
                    + "default `createdAt,desc`. Optional filters can be combined: `search` is a "
                    + "case-insensitive partial match on code and name; `status` is an exact match "
                    + "on ACTIVE or INACTIVE.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Zero-based page index.", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Page size (default 20, maximum 100).", example = "20"),
            @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Sort criteria as `property,asc|desc`; repeatable. Default `createdAt,desc`.", example = "code,asc"),
            @Parameter(name = "search", in = ParameterIn.QUERY, description = "Case-insensitive partial match on code and name.", example = "engineer"),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "Exact lifecycle status filter: ACTIVE or INACTIVE.", example = "ACTIVE")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of functions"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, sort or filter parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public Page<FunctionResponse> getAll(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) FunctionStatus status) {
        validateSort(pageable);
        return functionService.getAll(search, status, pageable);
    }

    @Operation(summary = "Update a function",
            description = "Replaces the mutable fields (code, name). The function status is "
                    + "not modified by this operation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Function updated"),
            @ApiResponse(responseCode = "400", description = "Malformed function id or invalid request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Function not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Function code already in use by another function",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/{id}")
    public FunctionResponse update(
            @Parameter(description = "UUID of the function", example = ID_EXAMPLE) @PathVariable UUID id,
            @Valid @RequestBody UpdateFunctionRequest request) {
        return functionService.update(id, request);
    }

    /**
     * Logical deactivation: the function status becomes {@code INACTIVE}. The row is kept.
     */
    @Operation(summary = "Deactivate a function",
            description = "Logical deletion: the status becomes INACTIVE and the row is kept. "
                    + "No response body is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Function deactivated"),
            @ApiResponse(responseCode = "400", description = "Malformed function id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Function not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "UUID of the function", example = ID_EXAMPLE) @PathVariable UUID id) {
        functionService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Activate a function",
            description = "Reactivates a deactivated function: the status becomes ACTIVE again. "
                    + "No row is created.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Function activated"),
            @ApiResponse(responseCode = "400", description = "Malformed function id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Function not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{id}/activate")
    public FunctionResponse activate(
            @Parameter(description = "UUID of the function", example = ID_EXAMPLE) @PathVariable UUID id) {
        return functionService.activate(id);
    }
}
