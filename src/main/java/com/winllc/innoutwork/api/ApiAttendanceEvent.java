package com.winllc.innoutwork.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** One check-in, lock, unlock or check-out from the Windows client, or an automatic check-out. */
@Schema(description = "One attendance event from the Windows client, or an automatic check-out")
public record ApiAttendanceEvent(
        @Schema(description = "Stable id", example = "918273") long id,
        @Schema(example = "CN=Jane Doe,OU=Users,DC=example,DC=com") String dn,
        @Schema(example = "Jane Doe") String name,
        @Schema(description = "CHECK_IN, LOCK, UNLOCK or CHECK_OUT", example = "CHECK_IN") String action,
        @Schema(description = "When (ISO-8601, in the person's time zone)", format = "date-time",
                example = "2026-10-21T08:02:11-04:00") String timestamp,
        @Schema(description = "The day it counts towards, in the person's time zone", format = "date",
                example = "2026-10-21") String date,
        @Schema(description = "A check-out made automatically after a long lock", example = "false") boolean forced,
        @Schema(example = "America/New_York") String timeZone) {
}
