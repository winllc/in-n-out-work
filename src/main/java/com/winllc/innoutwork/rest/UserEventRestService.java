package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.constant.StatusClass;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.service.Approvers;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.StatusCalendar;
import com.winllc.innoutwork.service.StatusPolicy;
import com.winllc.innoutwork.service.StatusRequestService;
import org.springframework.security.access.AccessDeniedException;
import com.winllc.innoutwork.service.ReportService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@RequiresFeature(LicensedFeature.CALENDAR)
@RequestMapping("/api/event")
@RestController
public class UserEventRestService {

    private static final Logger log = LoggerFactory.getLogger(UserEventRestService.class);

    private final UserEventRecordRepository userEventRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final ApplicationProperties properties;
    private final StatusPolicy statusPolicy;
    private final StatusCalendar statusCalendar;
    private final StatusRequestService requestService;
    private final Approvers approvers;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    public UserEventRestService(UserEventRecordRepository userEventRecordRepository,
                                CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties,
                                GlobalCalendarRecordRepository globalCalendarRecordRepository, StatusPolicy statusPolicy,
                                StatusCalendar statusCalendar, StatusRequestService requestService,
                                Approvers approvers) {
        this.userEventRecordRepository = userEventRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
        this.statusPolicy = statusPolicy;
        this.statusCalendar = statusCalendar;
        this.requestService = requestService;
        this.approvers = approvers;
    }


    /** A person's calendar statuses: as their details (AccessPolicy). */
    @GetMapping("/all")
    @PreAuthorize("@accessPolicy.canViewDetails(authentication, #dn)")
    public List<CalendarEvent> getEventForDay(Authentication authentication,
                                        @RequestParam String dn,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to){

        LdapDn ldapDn = LdapDn.builder().dn(dn).build();

        LocalDate fromDate = LocalDate.ofInstant(from, ZoneId.systemDefault());
        LocalDate toDate = LocalDate.ofInstant(to, ZoneId.systemDefault());

        List<UserEventRecord> byDnAndDateBetween = userEventRecordRepository.findByDnIgnoreCaseAndDateBetween(ldapDn.dn(), fromDate, toDate);

        // Requests still waiting for approval, shown as such: they excuse nothing yet.
        List<CalendarEvent> pending = new ArrayList<>();
        for (StatusRequest request : requestService.pendingBetween(ldapDn.dn(), fromDate, toDate)) {
            for (LocalDate day : EventRange.inclusive(request.getFromDate(), request.getToDate()).dates()) {
                if (!day.isBefore(fromDate) && !day.isAfter(toDate)) {
                    CalendarEvent event = new CalendarEvent(day, "Requested: " + request.getStatus().getFriendlyName());
                    event.setBackgroundColor(properties.getCalendarRequestedEventColor());
                    pending.add(event);
                }
            }
        }

        List<CalendarEvent> set = byDnAndDateBetween.stream()
                .map(r -> {
                    CalendarEvent event = new CalendarEvent(r.getDate(), r.getStatus().getFriendlyName());

                    if(r.getStatus().isExcusable()){
                        event.setBackgroundColor(properties.getCalendarStatusEventColor());
                    }else{
                        event.setBackgroundColor(properties.getCalendarAbsentStatusEventColor());
                    }

                    if(r.getStatus() == UserStatusEnum.LATE_ARRIVAL){
                        if(r.getLoginByTime() != null){
                            event.setTitle(r.getStatus().getFriendlyName()+ ": " + r.getLoginByTime());
                        }
                    }

                    return event;
                })
                .toList();
        List<CalendarEvent> all = new ArrayList<>(set);
        all.addAll(pending);
        return all;
    }

    /** The same with the hours they were online: as their details (AccessPolicy). */
    @GetMapping("/allWithLogins")
    @PreAuthorize("@accessPolicy.canViewDetails(authentication, #dn)")
    public List<CalendarEvent> getEventForDayWithLogins(Authentication authentication,
                                              @RequestParam String dn,
                                              @RequestParam Instant from,
                                              @RequestParam Instant to){

        LocalDate fromDate = LocalDate.ofInstant(from, ZoneId.systemDefault());
        LocalDate toDate = LocalDate.ofInstant(to, ZoneId.systemDefault());
        List<CalendarEvent> allEvents = new ArrayList<>();

        List<CalendarEvent> events = getEventForDay(authentication, dn, from, to);
        List<CalendarEvent> globalEvents = globalCalendarRecordsToEvents(fromDate, toDate);
        try {
            List<CalendarEvent> calendarEvents = auditRecordsToEvents(dn, fromDate, toDate);
            allEvents.addAll(calendarEvents);
        }catch (Exception e){
            log.error("Could not load login events for user %s between %s and %s".formatted(dn, fromDate.format(DATE_FORMATTER), toDate.format(DATE_FORMATTER)), e);
        }

        allEvents.addAll(events);
        allEvents.addAll(globalEvents);

        return allEvents;
    }

    /**
     * Puts a status on days of a calendar, {@code fromDate} up to (not including) {@code toDate}, at most
     * {@code application.events.max-range-days} of them (a longer, backwards or unreadable range is refused with 400
     * before anything is written). What happens depends on whose calendar and which status (StatusPolicy):
     * <ul>
     *   <li>one's own, a status the person sets (normal, early leave, late arrival): set at once;</li>
     *   <li>one's own, a status that excuses an absence (leave, telework, ...): becomes a request, which reaches the
     *       calendar only once approved (answer {@code "outcome":"requested"} with its id);</li>
     *   <li>one's own, a status only managers record (absent, unscheduled leave): refused, 403;</li>
     *   <li>someone else's ({@code dn}): any status, set at once, by an administrator or the person's manager or
     *       delegate (Approvers); anyone else is refused, 403.</li>
     * </ul>
     */
    @PostMapping("/update")
    public Map<String, Object> updateEventForDay(Authentication authentication,
                                                 @RequestBody UserEventData data){

        log.debug("updateEventForDay called by %s with data: %s".formatted(authentication.getName(), data));

        String me = authentication.getName();
        String target = StringUtils.isBlank(data.getDn()) ? me : data.getDn().strip();
        UserStatusEnum status = parseStatus(data.getStatus());
        EventRange range = EventRange.parse(data.getFromDate(), data.getToDate(),
                properties.getEvents().getMaxRangeDays());
        LocalTime loginByTime = parseTime(data.getLateArrivalTime(), status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.name());
        result.put("fromDate", range.from().toString());
        result.put("toDate", range.toExclusive().toString());

        if (!Approvers.sameDn(target, me)) {
            // For someone else: their manager, a delegate or an administrator, recording whatever applies.
            if (!approvers.mayDecide(authentication, target)) {
                throw new AccessDeniedException("%s may not set statuses for %s".formatted(me, target));
            }
            audit.inTransaction(() -> setStatuses(target, status, range, loginByTime, true));
            result.put("outcome", "set");
            return result;
        }

        StatusClass statusClass = statusPolicy.classify(status);
        switch (statusClass) {
            case USER_SELECTABLE -> {
                // A person's late arrival or early leave, as they set it on their calendar: saved and audited together.
                audit.inTransaction(() -> setStatuses(me, status, range, loginByTime, false));
                result.put("outcome", "set");
            }
            case REQUEST -> {
                StatusRequest request = requestService.create(me, status, range, loginByTime, data.getNote());
                result.put("outcome", "requested");
                result.put("requestId", request.getId());
                result.put("message", status.getFriendlyName()
                        + " needs approval: your manager has been asked. Until it is approved it does not excuse an absence.");
            }
            case ADMIN_ONLY -> throw new AccessDeniedException(
                    "%s may not record %s: managers and administrators record it".formatted(me, status));
        }
        return result;
    }

    /** The statuses the signed-in user may put on their own calendar, and whether each needs approval. */
    @GetMapping("/statuses")
    public List<Map<String, Object>> ownStatuses() {
        return statusPolicy.ownCalendarStatuses().stream()
                .map(s -> {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("status", s.name());
                    option.put("name", s.getFriendlyName());
                    option.put("needsApproval", statusPolicy.classify(s) == StatusClass.REQUEST);
                    return option;
                })
                .toList();
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidRequestException e) {
        return ApiErrors.of(e);
    }

    static UserStatusEnum parseStatus(String status) {
        try {
            return UserStatusEnum.valueOf(status == null ? "" : status.strip());
        } catch (IllegalArgumentException e) {
            throw InvalidRequestException.badRequest("invalid_status", "Unknown status: " + status);
        }
    }

    static LocalTime parseTime(String time, UserStatusEnum status) {
        if (status != UserStatusEnum.LATE_ARRIVAL || StringUtils.isBlank(time)) {
            return null;
        }
        try {
            return LocalTime.parse(time.strip(), DateTimeFormatter.ISO_TIME);
        } catch (DateTimeParseException e) {
            throw InvalidRequestException.badRequest("invalid_time", "lateArrivalTime is not a time (09:30)");
        }
    }

    private void setStatuses(String dn, UserStatusEnum status, EventRange range, LocalTime loginByTime,
                             boolean forSomeoneElse) {
        int days = statusCalendar.set(dn, status, range.dates(), loginByTime, null);
        AuditEntry entry = AuditEntry.of(AuditEntry.Category.DATA, "calendar.status-set")
                .target("user", dn, LdapDn.cnOf(dn))
                .detail("status", status.name())
                .detail("lateArrivalTime", loginByTime == null ? null : loginByTime.toString())
                .detail("from", range.from().toString())
                .detail("to", range.toExclusive().toString())
                .detail("days", days);
        if (forSomeoneElse) {
            entry.detail("via", "manager");
        }
        audit.record(entry);
    }

    private List<CalendarEvent> globalCalendarRecordsToEvents(LocalDate fromDate, LocalDate toDate){
        List<CalendarEvent> events = new ArrayList<>();

        List<GlobalCalendarRecord> records = globalCalendarRecordRepository.findByDateBetween(fromDate, toDate);

        for(GlobalCalendarRecord record : records){
            CalendarEvent event = new CalendarEvent(record.getDate(), record.getTitle());
            event.setBackgroundColor(properties.getCalendarGlobalEventColor());
            events.add(event);
        }

        return events;
    }

    private List<CalendarEvent> auditRecordsToEvents(String dn, LocalDate fromDate, LocalDate toDate){
        List<CalendarEvent> events = new ArrayList<>();

        ZonedDateTime fromDateTime = ZonedDateTime.ofLocal(fromDate.atStartOfDay(), ZoneId.systemDefault(), null);
        ZonedDateTime toDateTime = ZonedDateTime.ofLocal(toDate.atStartOfDay(), ZoneId.systemDefault(), null).plusDays(1).minusNanos(1);

        List<CheckInOutRecord> checkInOutRecords =
                checkInOutRecordRepository.findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(dn, fromDateTime, toDateTime);

        Map<LocalDate, List<CheckInOutRecord>> dateMap = ReportService.createDateMap(checkInOutRecords, fromDateTime, toDateTime);

        dateMap.forEach((date, records) -> {
            UserDayReport dayReport = UserDayReport.build(date, null, records);

            if(!dayReport.getCheckedInPeriod().equalsIgnoreCase("N/A")){
                CalendarEvent event = new CalendarEvent(date, "Online: " + dayReport.getCheckedInPeriod());
                event.setBackgroundColor(properties.getCalendarActivityEventColor());
                events.add(event);
            }
        });

        return events;
    }
}
