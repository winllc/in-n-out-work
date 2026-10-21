package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.ReportService;
import com.winllc.innoutwork.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@RequestMapping("/api/event")
@RestController
public class UserEventRestService {

    private static final Logger log = LoggerFactory.getLogger(UserEventRestService.class);

    private final UserEventRecordRepository userEventRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final ApplicationProperties properties;
    private final LdapService ldapService;
    private final UserService userService;

    public UserEventRestService(UserEventRecordRepository userEventRecordRepository,
                                CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties,
                                GlobalCalendarRecordRepository globalCalendarRecordRepository, LdapService ldapService,
                                UserService userService) {
        this.userEventRecordRepository = userEventRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
        this.ldapService = ldapService;
        this.userService = userService;
    }


    @GetMapping("/all")
    public List<CalendarEvent> getEventForDay(Authentication authentication,
                                        @RequestParam String dn,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to){

        LdapDn ldapDn = LdapDn.builder().dn(dn).build();

        LocalDate fromDate = LocalDate.ofInstant(from, ZoneId.systemDefault());
        LocalDate toDate = LocalDate.ofInstant(to, ZoneId.systemDefault());

        List<UserEventRecord> byDnAndDateBetween = userEventRecordRepository.findByDnIgnoreCaseAndDateBetween(ldapDn.dn(), fromDate, toDate);

        return byDnAndDateBetween.stream()
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
    }

    @GetMapping("/allWithLogins")
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
     * Every member of a group's self-set statuses over a date range, as one combined calendar.
     * <p>
     * Membership comes from the directory and the statuses from a single query over the whole
     * membership, so a group costs one directory read plus one select however many members it has.
     * Guarded exactly like the group page that draws it.
     */
    @GetMapping("/group/{groupDn}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER) " +
            "or @permissionEvaluator.groupCheck(#groupDn, #authentication)")
    public List<CalendarEvent> getGroupEvents(Authentication authentication,
                                              @PathVariable String groupDn,
                                              @RequestParam Instant from,
                                              @RequestParam Instant to) {

        return combinedCalendar("group " + groupDn,
                ldapService.getGroupMembers(LdapDn.builder().dn(groupDn).build()), from, to);
    }

    /**
     * The same combined calendar for the signed-in user's direct reports, behind the My Reports page.
     * <p>
     * Scoped to the caller by design, like {@code /api/users/reports}: the team is derived from the
     * authenticated principal rather than a parameter, so it cannot be pointed at somebody else's.
     * Open to any authenticated user for the reason the page itself is - reporting lines come from
     * the directory, so someone holding only USER can still have a team.
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public List<CalendarEvent> getDirectReportEvents(Authentication authentication,
                                                     @RequestParam Instant from,
                                                     @RequestParam Instant to) {

        List<String> reports = userService.findDirectReports(LdapDn.builder().dn(authentication.getName()).build())
                .stream()
                .map(LdapUser::getDn)
                .toList();

        return combinedCalendar("the reports of " + authentication.getName(), reports, from, to);
    }

    /**
     * One calendar holding the statuses every one of {@code memberDns} set for themselves.
     *
     * @param subject what the members are, for the log line only
     */
    private List<CalendarEvent> combinedCalendar(String subject, List<String> memberDns, Instant from, Instant to) {
        LocalDate fromDate = LocalDate.ofInstant(from, ZoneId.systemDefault());
        LocalDate toDate = LocalDate.ofInstant(to, ZoneId.systemDefault());

        // Lower-cased DN to the directory's spelling: the repository compares lower(dn), and the
        // spelling is what labels the event and links to the member.
        Map<String, String> members = new LinkedHashMap<>();
        for (String memberDn : memberDns) {
            if (StringUtils.isNotBlank(memberDn)) {
                members.putIfAbsent(memberDn.toLowerCase(), memberDn);
            }
        }

        if (members.isEmpty()) {
            // "in ()" is not valid SQL, so an empty membership must not reach the query.
            log.debug("No members for {}, so its calendar is empty", subject);
            return List.of();
        }

        List<UserEventRecord> records =
                userEventRecordRepository.findByLowercaseDnInAndDateBetween(members.keySet(), fromDate, toDate);

        log.debug("Calendar for {}: {} status entries for {} member(s) between {} and {}",
                subject, records.size(), members.size(), fromDate, toDate);

        return records.stream()
                .map(r -> groupEvent(r, members.getOrDefault(r.getDn().toLowerCase(), r.getDn())))
                .sorted(Comparator.comparing(CalendarEvent::getStart).thenComparing(CalendarEvent::getTitle))
                .toList();
    }

    /** One member's status on one day, named and linked back to that member. */
    private CalendarEvent groupEvent(UserEventRecord record, String memberDn) {
        UserStatusEnum status = record.getStatus();

        String label = status.getFriendlyName();
        if (status == UserStatusEnum.LATE_ARRIVAL && record.getLoginByTime() != null) {
            label = "%s by %s".formatted(label, record.getLoginByTime());
        }

        CalendarEvent event = new CalendarEvent(record.getDate(), "%s: %s".formatted(nameOf(memberDn), label));
        // The shared constructor derives the id from the title, which two namesakes on the same
        // status and day share. Keyed on the member instead, so every event stays distinct -
        // FullCalendar draws events that share an id as one event.
        event.setId("%s|%s|%s".formatted(record.getDate(), memberDn.toLowerCase(), status.name()));
        event.setBackgroundColor(status.isExcusable()
                ? properties.getCalendarStatusEventColor()
                : properties.getCalendarAbsentStatusEventColor());
        event.setUrl("/app/user/details/" + memberDn);

        return event;
    }

    /** The member's CN, falling back to the raw value so an odd member never loses its row. */
    private static String nameOf(String memberDn) {
        try {
            String cn = LdapDn.builder().dn(memberDn).build().getCn();
            return StringUtils.isNotBlank(cn) ? cn : memberDn;
        } catch (IllegalArgumentException e) {
            return memberDn;
        }
    }

    @PostMapping("/update")
    public UserEventData updateEventForDay(Authentication authentication,
                                             @RequestBody UserEventData data){

        log.debug("updateEventForDay called by %s with data: %s".formatted(authentication.getName(), data));

        String userDn = authentication.getName();
        LocalDate fromLocalDate = LocalDate.parse(data.getFromDate(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        LocalDate toLocalDate = LocalDate.parse(data.getToDate(), DateTimeFormatter.ISO_ZONED_DATE_TIME);

        while(fromLocalDate.isBefore(toLocalDate)){
            UserStatusEnum status = UserStatusEnum.valueOf(data.getStatus());
            UserEventRecord record = new UserEventRecord();
            record.setDn(userDn);
            record.setDate(fromLocalDate);
            if(StringUtils.isNotBlank(data.getLateArrivalTime()) && status == UserStatusEnum.LATE_ARRIVAL) {
                record.setLoginByTime(LocalTime.parse(data.getLateArrivalTime(), DateTimeFormatter.ISO_TIME));
            }

            List<UserEventRecord> byDnAndDate = userEventRecordRepository.findByDnIgnoreCaseAndDate(
                    userDn, fromLocalDate);

            Optional<UserEventRecord> userUpdatedRecord = byDnAndDate.stream()
                    .filter(r -> r.getStatus().isSelectable())
                    .findFirst();

            if(userUpdatedRecord.isPresent()){
                record = userUpdatedRecord.get();
            }

            record.setStatus(status);

            userEventRecordRepository.save(record);

            fromLocalDate = fromLocalDate.plusDays(1);

        }

        return data;
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
