package com.sdigital.platform.dashboard.controller;

import com.sdigital.platform.dashboard.dto.DashboardSummaryResponse;
import com.sdigital.platform.dashboard.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the dashboard module, versioned under {@code /api/v1/dashboard}.
 *
 * <p>The controller contains no business logic: it delegates to {@link DashboardService}
 * and only handles the HTTP contract. This first version exposes global statistics only
 * (no per-period, per-agency or per-user breakdown).</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Tableau de bord", description = "Statistiques globales en lecture seule de la plateforme.")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "Get the dashboard summary",
            description = "Returns global read-only statistics computed from the current data: "
                    + "total / active / inactive counts for users, agencies and functions, together "
                    + "with the generation timestamp. Restricted to ADMIN and MANAGER.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Global statistics",
                    content = @Content(schema = @Schema(implementation = DashboardSummaryResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Insufficient role or unlinked / inactive local identity",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/summary")
    public DashboardSummaryResponse getSummary() {
        return dashboardService.getSummary();
    }
}
