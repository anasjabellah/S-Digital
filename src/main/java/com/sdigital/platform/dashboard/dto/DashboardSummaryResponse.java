package com.sdigital.platform.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Global platform statistics returned by {@code GET /api/v1/dashboard/summary}.
 *
 * <p>This is a read-only aggregate of the existing data: it never exposes a JPA
 * entity and holds no business data beyond the requested counters. The three
 * families are independent and each is counted from its own table.</p>
 */
@Schema(name = "DashboardSummaryResponse", description = "Global platform statistics (users, agencies, functions).")
public record DashboardSummaryResponse(

        @Schema(description = "User statistics.")
        EntityStatusStatistics users,

        @Schema(description = "Agency statistics.")
        EntityStatusStatistics agencies,

        @Schema(description = "Function statistics.")
        EntityStatusStatistics functions,

        @Schema(description = "Timestamp at which the statistics were generated (UTC).", example = "2026-10-21T10:30:00Z")
        Instant generatedAt
) {
}
