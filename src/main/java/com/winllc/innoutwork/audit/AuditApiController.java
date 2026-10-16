package com.winllc.innoutwork.audit;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The pull API for SIEMs: {@code GET /api/audit/events?after=<seq>&limit=<1..1000>}, with
 * {@code Authorization: Bearer <token>} ({@link AuditApiSecurityConfig}). NDJSON by default, one full line per
 * event in ascending seq; {@code {"events":[...],"next":<seq>}} with {@code Accept: application/json}. Pass
 * {@code next} back as {@code after}. Works in every license state, and is not itself audited.
 */
@RestController
public class AuditApiController {

    public static final String PATH = "/api/audit/events";
    public static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final AuditQueryService query;

    public AuditApiController(AuditQueryService query) {
        this.query = query;
    }

    @GetMapping(PATH)
    public ResponseEntity<String> events(@RequestParam(defaultValue = "0") long after,
                                         @RequestParam(defaultValue = "500") int limit,
                                         @RequestHeader(value = "Accept", required = false) String accept) {
        if (limit < 1 || limit > 1000 || after < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"bad_request\",\"message\":\"after must be >= 0 and limit 1 to 1000\"}");
        }
        List<AuditEventRecord> events = query.after(after, limit);
        long next = events.isEmpty() ? after : events.getLast().getSeq();
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            StringBuilder b = new StringBuilder("{\"events\":[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(events.get(i).getLine());
            }
            b.append("],\"next\":").append(next).append('}');
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(b.toString());
        }
        StringBuilder b = new StringBuilder();
        for (AuditEventRecord e : events) {
            b.append(e.getLine()).append('\n');
        }
        return ResponseEntity.ok().contentType(NDJSON).header("X-Audit-Next", String.valueOf(next)).body(b.toString());
    }
}
