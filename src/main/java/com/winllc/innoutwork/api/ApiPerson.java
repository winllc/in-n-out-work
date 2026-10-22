package com.winllc.innoutwork.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A tracked person and their status today. The times are present only for people the token's owner may see the
 * details of; for everyone else the status is what a coworker sees (application.privacy.coworker-visibility).
 */
@Schema(description = "A tracked person and their status today. Times are present only for people whose details "
        + "the token's owner may see; for others the status is what a coworker sees (privacy settings), possibly null.")
public record ApiPerson(
        @Schema(description = "Directory DN: the person's id in this API",
                example = "CN=Jane Doe,OU=Users,DC=example,DC=com") String dn,
        @Schema(description = "Common name", example = "Jane Doe") String name,
        String email,
        String organization,
        String location,
        String employeeType,
        @Schema(description = "IN, AWAY, OUT or NONE (not seen today), or a calendar status such as WORK_FROM_HOME or "
                + "SCHEDULED_LEAVE; IN or OUT only, or null, under stricter privacy settings", example = "IN") String status,
        @Schema(description = "First check-in today (ISO-8601, the person's time zone)", format = "date-time",
                example = "2026-10-21T08:02:11-04:00") String checkedInAt,
        @Schema(description = "Last check-out today", format = "date-time") String checkedOutAt,
        @Schema(description = "Last change of presence today", format = "date-time") String lastStatusChangeAt,
        @Schema(description = "The IANA time zone the person's times are given in", example = "America/New_York")
        String timeZone,
        @Schema(description = "Whether the times and calendar status are shown (the owner may see this person's details)")
        boolean details) {
}
