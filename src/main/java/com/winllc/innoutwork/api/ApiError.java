package com.winllc.innoutwork.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** Every refusal and error on /api/v1. */
@Schema(description = "An error: a stable code and a sentence for people.")
public record ApiError(
        @Schema(description = "A stable code: unauthorized, forbidden, rate_limited, bad_request, not_licensed, ...",
                example = "bad_request") String error,
        @Schema(description = "What went wrong, for people", example = "from must be on or before to") String message) {
}
