package com.sdigital.platform.audit.controller;

import com.sdigital.platform.audit.dto.AuditLogFilter;
import com.sdigital.platform.audit.dto.AuditLogResponse;
import com.sdigital.platform.audit.entity.AuditLog;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * REST API for the consultation of the audit trail, versioned under
 * {@code /api/v1/audit-logs}.
 *
 * <p>The controller is strictly read-only: it exposes a single {@code GET} route, no
 * route can create, alter or delete an audit event. It contains no business logic: it
 * validates the request, builds the query criteria and delegates to
 * {@link AuditLogQueryService}.</p>
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Journal d'audit", description = "Consultation en lecture seule du journal d'audit de la plateforme.")
public class AuditLogController {

    /**
     * Sortable event properties. Anything else is rejected with 400 instead of
     * reaching the persistence layer: a client-provided property is never passed to a
     * query as is.
     */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "occurredAt", "action", "entityType", "outcome", "actorUserId");

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    /**
     * Rejects any sort property that is not explicitly allowed.
     */
    private static void validateSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PropertyReferenceException(order.getProperty(), TypeInformation.of(AuditLog.class), List.of());
            }
        }
    }

    @Operation(summary = "List audit events",
            description = "Returns a paginated, read-only view of the audit trail. Pagination and sorting "
                    + "are bound by Spring Data: `page` (0-based, default 0) and `size` (default 20, capped at "
                    + "100); non-numeric values fall back to their default. `sort` uses `property,asc|desc` and "
                    + "must reference one of `occurredAt`, `action`, `entityType`, `outcome` or `actorUserId`; "
                    + "default `occurredAt,desc`. The order is completed by a descending `id` tie-breaker, so two "
                    + "events sharing the same sort key never swap places between two pages. Optional filters can "
                    + "be combined: `action` and `entityType` are exact (case-sensitive) matches, `entityId` and "
                    + "`actorUserId` are UUIDs, `outcome` is one of SUCCESS, DENIED, FAILURE or NO_CHANGE. The time "
                    + "bounds `from` and `to` are inclusive ISO-8601 instants; `from` must not be after `to`.")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "Zero-based page index.", example = "0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "Page size (default 20, maximum 100).", example = "20"),
            @Parameter(name = "sort", in = ParameterIn.QUERY, description = "Sort criteria as `property,asc|desc`; repeatable. Default `occurredAt,desc`.", example = "occurredAt,desc"),
            @Parameter(name = "action", in = ParameterIn.QUERY, description = "Exact action code.", example = "USER_UPDATED"),
            @Parameter(name = "entityType", in = ParameterIn.QUERY, description = "Exact type of the affected resource.", example = "USER"),
            @Parameter(name = "entityId", in = ParameterIn.QUERY, description = "Identifier of the affected resource.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            @Parameter(name = "actorUserId", in = ParameterIn.QUERY, description = "Local identifier of the acting user.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6"),
            @Parameter(name = "outcome", in = ParameterIn.QUERY, description = "Outcome of the audited operation.", example = "SUCCESS"),
            @Parameter(name = "from", in = ParameterIn.QUERY, description = "Inclusive lower bound of the event date (ISO-8601 instant).", example = "2026-01-01T00:00:00Z"),
            @Parameter(name = "to", in = ParameterIn.QUERY, description = "Inclusive upper bound of the event date (ISO-8601 instant).", example = "2026-12-31T23:59:59Z")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of audit events"),
            @ApiResponse(responseCode = "400", description = "Invalid page, size, sort or filter parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient role or unlinked / inactive local identity",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    public Page<AuditLogResponse> getAll(
            @ParameterObject
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        validateSort(pageable);
        AuditLogFilter filter = new AuditLogFilter(
                action, entityType, entityId, actorUserId, outcome, from, to);
        return auditLogQueryService.search(filter, pageable);
    }
}
