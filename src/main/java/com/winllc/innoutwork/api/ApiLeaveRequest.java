package com.winllc.innoutwork.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** A request for a status over a range of days (leave, telework...), and what became of it. */
@Schema(description = "A request for a status over a range of days, and its decision")
public record ApiLeaveRequest(
        @Schema(example = "4512") long id,
        @Schema(example = "CN=Jane Doe,OU=Users,DC=example,DC=com") String dn,
        @Schema(example = "Jane Doe") String name,
        @Schema(example = "SCHEDULED_LEAVE") String status,
        @Schema(example = "Scheduled leave") String statusName,
        @Schema(format = "date", example = "2026-10-23") String fromDate,
        @Schema(description = "The last day, included", format = "date", example = "2026-10-24") String toDate,
        @Schema(description = "REQUESTED, APPROVED, REJECTED or CANCELLED", example = "APPROVED") String state,
        @Schema(format = "date-time") String createdAt,
        @Schema(description = "Who approved or rejected it") String decidedBy,
        @Schema(format = "date-time") String decidedAt) {
}
