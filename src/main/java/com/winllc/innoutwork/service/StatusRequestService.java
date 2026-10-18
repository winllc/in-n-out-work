package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.StatusClass;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.metrics.EnsMetrics;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Status requests (RR-07): a person asks for a status that excuses an absence (StatusPolicy's REQUEST class) over a
 * range of days, and it reaches their calendar only when someone {@link Approvers} names approves it. Until then
 * nothing excuses them: the absence alerts read the calendar, and a request is not on it.
 *
 * <p>Each step is saved and audited together ({@code calendar.request-created}, {@code -approved}, {@code -rejected},
 * {@code -cancelled}; ids, dates and states only, never the notes), and e-mailed once committed when a mail server
 * is configured: a new request to its approvers, a decision to the requester, a cancellation to the approvers.
 */
@Service
public class StatusRequestService {

    private static final Logger log = LoggerFactory.getLogger(StatusRequestService.class);

    static final String MAIL_TEMPLATE = "request-notification.html";

    private final StatusRequestRepository requests;
    private final StatusCalendar calendar;
    private final StatusPolicy policy;
    private final Approvers approvers;
    private final AccessPolicy accessPolicy;
    private final Mailer mailer;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;
    private final Clock clock;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired
    public StatusRequestService(StatusRequestRepository requests, StatusCalendar calendar, StatusPolicy policy,
                                Approvers approvers, AccessPolicy accessPolicy, Mailer mailer,
                                ApplicationProperties properties,
                                @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache) {
        this(requests, calendar, policy, approvers, accessPolicy, mailer, properties, userCache, Clock.systemDefaultZone());
    }

    StatusRequestService(StatusRequestRepository requests, StatusCalendar calendar, StatusPolicy policy,
                         Approvers approvers, AccessPolicy accessPolicy, Mailer mailer, ApplicationProperties properties,
                         LoadingCache<String, LdapUser> userCache, Clock clock) {
        this.requests = requests;
        this.calendar = calendar;
        this.policy = policy;
        this.approvers = approvers;
        this.accessPolicy = accessPolicy;
        this.mailer = mailer;
        this.properties = properties;
        this.userCache = userCache;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    // --- asking -------------------------------------------------------------------------------------------------

    /**
     * {@code requesterDn} asks for {@code status} on the days of {@code range} (already held to
     * application.events.max-range-days).
     *
     * @throws InvalidRequestException (400) when the status is not one that is requested
     */
    public StatusRequest create(String requesterDn, UserStatusEnum status, EventRange range, LocalTime loginByTime,
                                String note) {
        if (policy.classify(status) != StatusClass.REQUEST) {
            throw InvalidRequestException.badRequest("not_a_request_status",
                    status.getFriendlyName() + " is not requested: " + (policy.classify(status) == StatusClass.ADMIN_ONLY
                            ? "only a manager or an administrator records it" : "set it on your calendar"));
        }
        StatusRequest request = StatusRequest.builder()
                .requesterDn(requesterDn)
                .status(status)
                .fromDate(range.from())
                .toDate(range.lastDay())
                .loginByTime(status == UserStatusEnum.LATE_ARRIVAL ? loginByTime : null)
                .note(trim(note))
                .state(RequestState.REQUESTED)
                .createdAt(ZonedDateTime.now(clock))
                .build();

        StatusRequest saved = audit.inTransaction(() -> {
            StatusRequest stored = requests.save(request);
            audit.record(entry("calendar.request-created", stored));
            return stored;
        });
        // Committed: now the mail, which looks the approvers up in the directory, outside the transaction.
        EnsMetrics.request("created");
        mailApprovers(saved, "requested");
        log.info("Status request {}: {} asked for {} from {} to {}", saved.getId(), requesterDn, status,
                saved.getFromDate(), saved.getToDate());
        return saved;
    }

    // --- deciding -----------------------------------------------------------------------------------------------

    /**
     * Approves a pending request: its status goes on the requester's calendar for each of its days, replacing what
     * they had set there.
     *
     * @throws AccessDeniedException   unless {@code viewer} decides for the requester (Approvers), and never their own
     * @throws InvalidRequestException (404, 409) for a request that does not exist or is no longer pending
     */
    public StatusRequest approve(Authentication viewer, long id, String comment) {
        return decide(viewer, id, comment, RequestState.APPROVED);
    }

    /** Rejects a pending request; nothing reaches the calendar. As {@link #approve} otherwise. */
    public StatusRequest reject(Authentication viewer, long id, String comment) {
        return decide(viewer, id, comment, RequestState.REJECTED);
    }

    private StatusRequest decide(Authentication viewer, long id, String comment, RequestState decision) {
        // Who may decide is asked first, without the lock: it can take a directory lookup, and the requester of a
        // request never changes.
        String requesterDn = requests.findById(id).orElseThrow(() -> notFound(id)).getRequesterDn();
        if (!approvers.mayDecide(viewer, requesterDn)) {
            throw new AccessDeniedException("%s may not decide request %d of %s"
                    .formatted(viewer.getName(), id, requesterDn));
        }
        StatusRequest decided = audit.inTransaction(() -> {
            StatusRequest request = requests.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
            if (!request.getState().isOpen()) {
                throw InvalidRequestException.conflict("not_pending",
                        "Request %d is already %s".formatted(id, request.getState().name().toLowerCase()));
            }
            request.setState(decision);
            request.setDecidedByDn(viewer.getName());
            request.setDecidedAt(ZonedDateTime.now(clock));
            request.setDecisionNote(trim(comment));
            StatusRequest stored = requests.save(request);
            int days = 0;
            if (decision == RequestState.APPROVED) {
                days = calendar.set(stored.getRequesterDn(), stored.getStatus(),
                        EventRange.inclusive(stored.getFromDate(), stored.getToDate()).dates(),
                        stored.getLoginByTime(), stored.getId());
            }
            audit.record(entry(decision == RequestState.APPROVED ? "calendar.request-approved" : "calendar.request-rejected",
                    stored).detail("daysWritten", days));
            return stored;
        });
        EnsMetrics.request(decision.name());
        mailRequester(decided);
        log.info("Status request {} {} by {}", id, decision.name().toLowerCase(), viewer.getName());
        return decided;
    }

    // --- withdrawing --------------------------------------------------------------------------------------------

    /**
     * Cancels a request: the requester's own, or any as an administrator. A pending one simply closes. An approved one
     * can be cancelled until its last day has passed: its days from today on come off the calendar, earlier ones stay
     * as the record of what was approved.
     */
    public StatusRequest cancel(Authentication viewer, long id) {
        StatusRequest cancelled = audit.inTransaction(() -> {
            StatusRequest request = requests.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
            boolean own = Approvers.sameDn(viewer.getName(), request.getRequesterDn());
            if (!own && !accessPolicy.isAdmin(viewer)) {
                throw new AccessDeniedException("%s may not cancel request %d of %s"
                        .formatted(viewer.getName(), id, request.getRequesterDn()));
            }
            LocalDate today = LocalDate.now(clock);
            RequestState was = request.getState();
            boolean cancellable = was == RequestState.REQUESTED
                    || (was == RequestState.APPROVED && !request.getToDate().isBefore(today));
            if (!cancellable) {
                throw InvalidRequestException.conflict("not_cancellable", was == RequestState.APPROVED
                        ? "Request %d is over; there is nothing left to cancel".formatted(id)
                        : "Request %d is already %s".formatted(id, was.name().toLowerCase()));
            }
            int removed = was == RequestState.APPROVED ? calendar.removeRequestDays(request.getId(), today) : 0;
            request.setState(RequestState.CANCELLED);
            request.setCancelledByDn(viewer.getName());
            request.setCancelledAt(ZonedDateTime.now(clock));
            StatusRequest stored = requests.save(request);
            audit.record(entry("calendar.request-cancelled", stored)
                    .detail("previousState", was.name())
                    .detail("daysRemoved", removed));
            return stored;
        });
        EnsMetrics.request("cancelled");
        mailApprovers(cancelled, "cancelled");
        log.info("Status request {} cancelled by {}", id, viewer.getName());
        return cancelled;
    }

    // --- reading ------------------------------------------------------------------------------------------------

    /** {@code dn}'s own requests, newest first. */
    public List<StatusRequest> mine(String dn) {
        return requests.findByRequester(dn);
    }

    /**
     * The pending requests {@code viewer} may decide, oldest first: every one but their own for an administrator,
     * otherwise those of the people they decide for (Approvers).
     */
    public List<StatusRequest> pendingFor(Authentication viewer) {
        if (accessPolicy.isAdmin(viewer)) {
            return requests.findByStateOrderByCreatedAtAsc(RequestState.REQUESTED).stream()
                    .filter(r -> !Approvers.sameDn(r.getRequesterDn(), viewer.getName()))
                    .toList();
        }
        Set<String> people = approvers.peopleDecidedBy(viewer.getName());
        if (people.isEmpty()) {
            return List.of();
        }
        return requests.findByStateAndLowercaseRequesterIn(RequestState.REQUESTED, people);
    }

    /** {@code dn}'s pending requests covering any day from {@code from} to {@code to}, for their calendar. */
    public List<StatusRequest> pendingBetween(String dn, LocalDate from, LocalDate to) {
        return requests.findByRequesterAndStateOverlapping(dn, RequestState.REQUESTED, from, to);
    }

    /** One request, for those who may see it: the requester, those who decide for them, administrators. */
    public StatusRequest get(Authentication viewer, long id) {
        StatusRequest request = requests.findById(id).orElseThrow(() -> notFound(id));
        if (!Approvers.sameDn(viewer.getName(), request.getRequesterDn()) && !approvers.mayDecide(viewer,
                request.getRequesterDn())) {
            throw new AccessDeniedException("%s may not see request %d".formatted(viewer.getName(), id));
        }
        return request;
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    /** The audit event: what, whose and when; never the requester's note or the approver's comment. */
    private static AuditEntry entry(String action, StatusRequest request) {
        return AuditEntry.of(AuditEntry.Category.DATA, action)
                .target("user", request.getRequesterDn(), LdapDn.cnOf(request.getRequesterDn()))
                .detail("requestId", request.getId())
                .detail("status", request.getStatus().name())
                .detail("from", request.getFromDate().toString())
                .detail("to", request.getToDate().toString())
                .detail("days", request.getDays())
                .detail("state", request.getState().name());
    }

    private static InvalidRequestException notFound(long id) {
        return new InvalidRequestException(org.springframework.http.HttpStatus.NOT_FOUND, "not_found",
                "No request " + id);
    }

    private static String trim(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= StatusRequest.NOTE_LENGTH ? stripped : stripped.substring(0, StatusRequest.NOTE_LENGTH);
    }

    private void mailApprovers(StatusRequest request, String what) {
        if (!mailer.isConfigured()) {
            return;
        }
        for (String approverDn : approvers.of(request.getRequesterDn())) {
            mail(approverDn, request, what, "/app/approvals");
        }
    }

    private void mailRequester(StatusRequest request) {
        if (mailer.isConfigured()) {
            mail(request.getRequesterDn(), request, request.getState().name().toLowerCase(), "/app/approvals");
        }
    }

    private void mail(String toDn, StatusRequest request, String what, String path) {
        LdapUser to;
        try {
            to = userCache.get(toDn);
        } catch (RuntimeException e) {
            log.warn("Could not look up {} to e-mail them about request {}", toDn, request.getId(), e);
            return;
        }
        if (to == null || to.getEmail() == null) {
            log.warn("No e-mail address for {}; not told about request {}", toDn, request.getId());
            return;
        }
        Map<String, Object> model = new HashMap<>();
        model.put("to", LdapDn.cnOf(toDn));
        model.put("what", what);
        model.put("requester", request.getRequesterCn());
        model.put("status", request.getStatus().getFriendlyName());
        model.put("from", DateTimeConstants.DATE_FORMATTER.format(request.getFromDate()));
        model.put("until", DateTimeConstants.DATE_FORMATTER.format(request.getToDate()));
        model.put("days", request.getDays());
        model.put("decidedBy", request.getDecidedByCn());
        model.put("url", properties.getApplicationBaseUrl() + path);
        String subject = switch (what) {
            case "requested" -> "Approval needed: " + request.getStatus().getFriendlyName() + " for " + request.getRequesterCn();
            case "cancelled" -> "Cancelled: " + request.getStatus().getFriendlyName() + " for " + request.getRequesterCn();
            default -> "Your " + request.getStatus().getFriendlyName() + " request was " + what;
        };
        mailer.sendAfterCommit(to.getEmail(), subject, MAIL_TEMPLATE, model);
    }
}
