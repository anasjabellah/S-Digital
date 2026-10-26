package com.sdigital.platform.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status statistics for a single entity family (users, agencies or functions).
 *
 * <p>{@code total} is the total number of rows in the family and {@code active} /
 * {@code inactive} are the counts per lifecycle status. Each family is counted
 * independently; no join between families is involved, so the counts never
 * double-count rows through relationships.</p>
 */
@Schema(name = "EntityStatusStatistics", description = "Total / active / inactive counts for one entity family.")
public record EntityStatusStatistics(

        @Schema(description = "Total number of records.", example = "25")
        long total,

        @Schema(description = "Number of records in ACTIVE status.", example = "20")
        long active,

        @Schema(description = "Number of records in INACTIVE status.", example = "5")
        long inactive
) {
}
