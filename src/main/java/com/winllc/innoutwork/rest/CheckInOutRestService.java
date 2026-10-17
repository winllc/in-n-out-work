package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.CheckInOutResponse;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.CheckInOut;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.metrics.EnsMetrics;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.security.ClientRateLimiter;
import com.winllc.innoutwork.service.CheckInOutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.kerberos.authentication.KerberosServiceRequestToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Windows client's status events, and an administrator's view of the day's records.
 * <p>
 * Who an event belongs to comes from the sign-in, never from the request body. The exception is the logoff, which
 * runs after the user's profile has gone and usually cannot sign in: it is identified by the session id the
 * logon's response carried (see CheckInOutService#saveSessionCheckOut), which works once and for a limited time.
 * Refused ones are rate-limited per client address; accepted ones never are.
 */
@RequiresFeature(LicensedFeature.ATTENDANCE)
@RestController
@RequestMapping("/api/check")
public class CheckInOutRestService {

    private static final Logger log = LoggerFactory.getLogger(CheckInOutRestService.class);

    private final CheckInOutService checkInOutService;
    /** How long a logon's session id can identify its logoff. */
    private final Duration sessionMaxAge;
    /** Refused logoffs without a sign-in, per client address. */
    private final ClientRateLimiter refusedLogoffs;

    /** Records the logoff call's unknown session ids as failed sign-ins; absent in test slices. */
    private SecurityAudit securityAudit;

    public CheckInOutRestService(CheckInOutService checkInOutService, ApplicationProperties properties) {
        this.checkInOutService = checkInOutService;
        this.sessionMaxAge = Duration.ofHours(properties.getLogoff().getSessionMaxAgeHours());
        this.refusedLogoffs = new ClientRateLimiter(properties.getLogoff().getRefusedPerMinute());
    }

    @Autowired(required = false)
    public void setSecurityAudit(SecurityAudit securityAudit) {
        this.securityAudit = securityAudit;
    }

    @PostMapping("/{action}")
    public ResponseEntity<?> checkIn(Authentication auth, HttpServletRequest request,
                                     @PathVariable String action,
                                     @RequestBody CheckInOut checkInOut) {
        log.debug("Status change {} request received: {}, for user: {}", action, checkInOut, auth != null ? auth.getName() : "NONE");

        CheckInOutEnum event = switch (action) {
            case "in" -> CheckInOutEnum.CHECK_IN;
            case "lock" -> CheckInOutEnum.LOCK;
            case "unlock" -> CheckInOutEnum.UNLOCK;
            case "out" -> CheckInOutEnum.CHECK_OUT;
            default -> null;
        };
        if (event == null) {
            return refused(HttpStatus.BAD_REQUEST, "invalid_action", "Unknown action " + action + "; expected in, lock, unlock or out.");
        }

        boolean signedIn = isDirectoryUser(auth);
        if (!signedIn && event != CheckInOutEnum.CHECK_OUT) {
            // The security chain lets none of these through without a sign-in; this covers one with no directory user.
            return refused(HttpStatus.UNAUTHORIZED, "not_signed_in", "Sign in with a certificate or Windows to report " + action + ".");
        }

        // After Windows sign-in the account name comes from the verified ticket, not the request body.
        String windowsUserId = checkInOut.getWindowsUserId();
        if (auth instanceof KerberosServiceRequestToken kerberos && kerberos.getTicketValidation() != null) {
            windowsUserId = kerberos.getTicketValidation().username();
        }

        CheckInOutRecord record = CheckInOutRecord.builder()
                .dn(signedIn ? LdapDn.normalize(auth.getName()) : null)
                .windowsUserId(windowsUserId)
                .action(event)
                .timestamp(ZonedDateTime.now(ZoneId.systemDefault()))
                .build();

        if(signedIn && auth.getPrincipal() instanceof AppUserDetails details){
            record.setOrganization(details.getOrganization());
            record.setEmployeeType(details.getEmployeeType());
            record.setLocation(details.getLocation());
            record.setBranch(details.getBranch());
            record.setDutySubOrganization(details.getDutySubOrganization());
        }

        if (event != CheckInOutEnum.CHECK_OUT) {
            // The signed-in user's own event: a session id in the body does not change whose.
            CheckInOutRecord saved = checkInOutService.saveCheckInOutRecord(record);
            EnsMetrics.checkIn(action);
            return ResponseEntity.ok(event == CheckInOutEnum.CHECK_IN
                    ? CheckInOutResponse.forCheckIn(saved)
                    : CheckInOutResponse.of(saved));
        }

        if (signedIn) {
            // Refused (403) if the session id is still usable and someone else's.
            CheckInOutRecord saved = checkInOutService.saveSignedInCheckOut(record, checkInOut.getSessionId(), sessionMaxAge);
            EnsMetrics.checkIn(action);
            return ResponseEntity.ok(CheckInOutResponse.of(saved));
        }

        // The logoff that cannot sign in: identified only by the session id cached at logon. A usable one is
        // accepted whatever else the address has sent: behind a proxy or NAT every workstation shares the address,
        // and the client does not retry, so a refusal would lose the check-out.
        Optional<CheckInOutRecord> saved = checkInOutService.saveSessionCheckOut(record, checkInOut.getSessionId(), sessionMaxAge);
        if (saved.isPresent()) {
            EnsMetrics.checkIn(action);
            return ResponseEntity.ok(CheckInOutResponse.of(saved.get()));
        }

        // Refused. Only these count towards the address's limit; past it they are no longer audited one by one.
        ClientRateLimiter.Outcome outcome = refusedLogoffs.count(request.getRemoteAddr());
        if (outcome == ClientRateLimiter.Outcome.WITHIN) {
            EnsMetrics.logoffRefused("unknown_session");
            if (securityAudit != null) {
                securityAudit.unknownClientSession(request);
            }
            return refused(HttpStatus.UNAUTHORIZED, "unknown_session",
                    "The session id is unknown, already used or expired; sign in to report the logoff.");
        }
        if (outcome == ClientRateLimiter.Outcome.FIRST_OVER) {
            log.warn("{} sent more than {} logoffs with an unusable session id this minute; refusing them with 429 "
                    + "(application.logoff.refused-per-minute)", request.getRemoteAddr(), refusedLogoffs.perMinute());
            if (securityAudit != null) {
                securityAudit.refusedClientSessionsLimited(request, refusedLogoffs.perMinute());
            }
        }
        EnsMetrics.logoffRefused("rate_limited");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ClientRateLimiter.WINDOW.toSeconds()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(error("rate_limited", "Too many logoffs with an unusable session id from this address; "
                        + "try again in a minute."));
    }

    /** A person in the directory: AppUserDetailsService gives each one USER, and no other principal holds it. */
    private static boolean isDirectoryUser(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().anyMatch(a -> UserRoleEnum.USER.name().equals(a.getAuthority()));
    }

    /** JSON, like the license's refusals: the Windows client logs the status and the message. */
    private static ResponseEntity<?> refused(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error(code, message));
    }

    private static Map<String, String> error(String code, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    /** Every record of the viewed day, for administrators. Records never carry their session id (see the model). */
    @GetMapping("/records")
    @PreAuthorize("hasAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN)")
    public Map<String, Object> getRecords(HttpSession session,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) String sortField,
                                          @RequestParam(required = false) String sortDir) {

        Sort sort = Sort.unsorted();
        if (sortField != null && sortDir != null) {
            sort = sortDir.equalsIgnoreCase("desc")
                    ? Sort.by(sortField).descending()
                    : Sort.by(sortField).ascending();
        }

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<CheckInOutRecord> userPage = checkInOutService.findRecords(pageable, session);

        Map<String, Object> response = new HashMap<>();
        response.put("data", userPage.getContent());
        response.put("last_page", userPage.getTotalPages());
        response.put("total", userPage.getTotalElements());
        response.put("page", userPage.getNumber());

        return response;
    }
}
