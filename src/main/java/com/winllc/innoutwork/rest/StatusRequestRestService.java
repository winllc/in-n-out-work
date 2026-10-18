package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.StatusRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Status requests (StatusRequestService) for scripts and the pages' fetch() calls; the approvals page
 * (/app/approvals) does the same with forms. Who may do what is decided in the service, on every call:
 * <ul>
 *   <li>{@code POST /api/requests}: ask for a status on one's own days ({@code status}, {@code fromDate},
 *       {@code toDate} exclusive, optional {@code lateArrivalTime} and {@code note}), held to
 *       application.events.max-range-days;</li>
 *   <li>{@code GET /api/requests/mine}, {@code /pending}: one's own requests; the pending ones one may decide;</li>
 *   <li>{@code POST /api/requests/{id}/approve}, {@code /reject} (optional {@code comment}): the requester's manager,
 *       a delegate or an administrator, never the requester; {@code /cancel}: the requester or an administrator.</li>
 * </ul>
 * Refusals: 403 for who asks, 400/404/409 with {@code {"error","message"}} for what is asked.
 */
@RequiresFeature(LicensedFeature.CALENDAR)
@RestController
@RequestMapping("/api/requests")
public class StatusRequestRestService {

    private final StatusRequestService requests;
    private final ApplicationProperties properties;

    public StatusRequestRestService(StatusRequestService requests, ApplicationProperties properties) {
        this.requests = requests;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<StatusRequest> create(Authentication authentication, @RequestBody UserEventData data) {
        EventRange range = EventRange.parse(data.getFromDate(), data.getToDate(),
                properties.getEvents().getMaxRangeDays());
        var status = UserEventRestService.parseStatus(data.getStatus());
        StatusRequest created = requests.create(authentication.getName(), status, range,
                UserEventRestService.parseTime(data.getLateArrivalTime(), status), data.getNote());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/mine")
    public List<StatusRequest> mine(Authentication authentication) {
        return requests.mine(authentication.getName());
    }

    @GetMapping("/pending")
    public List<StatusRequest> pending(Authentication authentication) {
        return requests.pendingFor(authentication);
    }

    @GetMapping("/{id}")
    public StatusRequest get(Authentication authentication, @PathVariable long id) {
        return requests.get(authentication, id);
    }

    @PostMapping("/{id}/approve")
    public StatusRequest approve(Authentication authentication, @PathVariable long id,
                                 @RequestBody(required = false) Map<String, String> body) {
        return requests.approve(authentication, id, comment(body));
    }

    @PostMapping("/{id}/reject")
    public StatusRequest reject(Authentication authentication, @PathVariable long id,
                                @RequestBody(required = false) Map<String, String> body) {
        return requests.reject(authentication, id, comment(body));
    }

    @PostMapping("/{id}/cancel")
    public StatusRequest cancel(Authentication authentication, @PathVariable long id) {
        return requests.cancel(authentication, id);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidRequestException e) {
        return ApiErrors.of(e);
    }

    private static String comment(Map<String, String> body) {
        return body == null ? null : body.get("comment");
    }
}
