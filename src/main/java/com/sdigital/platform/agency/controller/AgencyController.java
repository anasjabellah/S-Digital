package com.sdigital.platform.agency.controller;

import com.sdigital.platform.agency.dto.AgencyResponse;
import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.service.AgencyService;
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
 * REST API for the agency module, versioned under {@code /api/v1/agencies}.
 *
 * <p>The controller contains no business logic: it delegates to {@link AgencyService}
 * and only handles HTTP concerns (status codes, {@code Location} header, binding).</p>
 */
@RestController
@RequestMapping("/api/v1/agencies")
@Tag(name = "Agencies", description = "Create, read, update and deactivate platform agencies.")
public class AgencyController {

    private static final String ID_EXAMPLE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    /**
     * Sortable agency properties. Anything else (in particular technical fields) is
     * rejected with 400 instead of reaching the persistence layer.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "id", "code", "name", "status", "createdAt", "updatedAt");

    private final AgencyService agencyService;

    public AgencyController(AgencyService agencyService) {
        this.agencyService = agencyService;
    }

    private static void validateSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PropertyReferenceException(order.getProperty(), TypeInformation.of(Agency.class), List.of());
            }
        }
    }

    @Operation(summary = "Create an agency",
            description = "Creates a new agency in ACTIVE status. The response carries a "
                    + "`Location` header pointing to the created resource.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Agency created"),
            @ApiResponse(responseCode = "400", description = "Invalid or unreadable request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Agency code already in use",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<AgencyResponse> create(@Valid @RequestBody CreateAgencyRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        AgencyResponse created = agencyService.create(request);
        URI location = uriBuilder.path("/api/v1/agencies/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "Get an agency by id", description = "Returns a single agency by its UUID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agency found"),
            @ApiResponse(responseCode = "400", description = "Malformed agency id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agency not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{id}")
    public AgencyResponse getById(
            @Parameter(description = "UUID of the agency", example = ID_EXAMPLE) @PathVariable UUID id) {
        return agencyService.getById(id);
    }

    /**
     * Returns a page of agencies. Pagination and sorting are bound by Spring Data
     * ({@code page}, {@code size}, {@code sort}); the maximum page size is capped
     * globally through {@code spring.data.web.pageable.max-page-size}. Optional
     * multicriteria filters ({@code search}, {@code status}) narrow the listing.
     */
    @Operation(summary = "List agencies",
            description = "Returns a paginated list of agencies. Pagination and sorting are bound by "
                    + "Spring Data: `page` (0-based, default 0) and `size` (default 20, capped at 100); "
                    + "non-numeric values fall back to their default. `sort` uses `property,asc|desc` and "
                    + "must reference an existing agency property (e.g. `code`, `createdAt`); "
                    + "default `createdAt,desc`. Optional filters can be combined: `search` is a "
                    + "case-insensitive partial match on code and name; `status` is an exact match "
                    + "on ACTIVE or INACTIVE.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Zero-based page index.", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Page size (default 20, maximum 100).", example = "20"),
            @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Sort criteria as `property,asc|desc`; repeatable. Default `createdAt,desc`.", example = "code,asc"),
            @Parameter(name = "search", in = ParameterIn.QUERY, description = "Case-insensitive partial match on code and name.", example = "paris"),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "Exact lifecycle status filter: ACTIVE or INACTIVE.", example = "ACTIVE")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of agencies"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, sort or filter parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public Page<AgencyResponse> getAll(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AgencyStatus status) {
        validateSort(pageable);
        return agencyService.getAll(search, status, pageable);
    }

    @Operation(summary = "Update an agency",
            description = "Replaces the mutable fields (code, name). The agency status is "
                    + "not modified by this operation.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agency updated"),
            @ApiResponse(responseCode = "400", description = "Malformed agency id or invalid request body",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agency not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Agency code already in use by another agency",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/{id}")
    public AgencyResponse update(
            @Parameter(description = "UUID of the agency", example = ID_EXAMPLE) @PathVariable UUID id,
            @Valid @RequestBody UpdateAgencyRequest request) {
        return agencyService.update(id, request);
    }

    /**
     * Logical deactivation: the agency status becomes {@code INACTIVE}. The row is kept.
     */
    @Operation(summary = "Deactivate an agency",
            description = "Logical deletion: the status becomes INACTIVE and the row is kept. "
                    + "No response body is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Agency deactivated"),
            @ApiResponse(responseCode = "400", description = "Malformed agency id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agency not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @Parameter(description = "UUID of the agency", example = ID_EXAMPLE) @PathVariable UUID id) {
        agencyService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Activate an agency",
            description = "Reactivates a deactivated agency: the status becomes ACTIVE again. "
                    + "No row is created.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agency activated"),
            @ApiResponse(responseCode = "400", description = "Malformed agency id",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Agency not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{id}/activate")
    public AgencyResponse activate(
            @Parameter(description = "UUID of the agency", example = ID_EXAMPLE) @PathVariable UUID id) {
        return agencyService.activate(id);
    }
}
