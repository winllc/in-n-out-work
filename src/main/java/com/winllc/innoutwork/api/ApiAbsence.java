package com.winllc.innoutwork.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** A day with a status other than a standard working day on someone's calendar. */
@Schema(description = "A day on someone's calendar with a status other than a standard working day")
public record ApiAbsence(
        @Schema(example = "CN=Jane Doe,OU=Users,DC=example,DC=com") String dn,
        @Schema(example = "Jane Doe") String name,
        @Schema(format = "date", example = "2026-10-23") String date,
        @Schema(description = "OUT_OF_OFFICE, WORK_FROM_HOME, TDY, SCHEDULED_LEAVE, UNSCHEDULED_LEAVE, ABSENT_EXCUSED, "
                + "ABSENT_UNEXCUSED, ABSENT, EARLY_LEAVE or LATE_ARRIVAL", example = "SCHEDULED_LEAVE") String status,
        @Schema(example = "Scheduled leave") String statusName,
        @Schema(description = "request: an approved leave request; self: set by the person; manager: recorded by a "
                + "manager in answer to an absence alert", example = "request") String source,
        @Schema(description = "The approved request it comes from, for source request", example = "4512") Long requestId,
        @Schema(description = "For LATE_ARRIVAL: the expected arrival time", example = "10:30") String loginByTime) {
}
