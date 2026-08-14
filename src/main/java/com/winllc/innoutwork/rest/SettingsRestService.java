package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.data.CalendarEventData;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@RequestMapping("/api/settings/calendar")
@RestController
public class SettingsRestService {

    private static final Logger log = LoggerFactory.getLogger(SettingsRestService.class);

    private final UserEventRecordRepository userEventRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final ApplicationProperties properties;

    public SettingsRestService(UserEventRecordRepository userEventRecordRepository,
                               CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties, GlobalCalendarRecordRepository globalCalendarRecordRepository) {
        this.userEventRecordRepository = userEventRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.globalCalendarRecordRepository = globalCalendarRecordRepository;
    }


    @GetMapping("/all")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public List<CalendarEvent> getEventForDay(Authentication authentication,
                                        @RequestParam Instant from,
                                        @RequestParam Instant to){

        LocalDate fromDate = LocalDate.ofInstant(from, ZoneId.systemDefault());
        LocalDate toDate = LocalDate.ofInstant(to, ZoneId.systemDefault());

        return globalCalendarRecordsToEvents(fromDate, toDate);
    }



    @PostMapping("/update")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public CalendarEventData updateCalendar(Authentication authentication,
                                             @RequestBody CalendarEventData data){

        log.debug("updateCalendar called by %s with data: %s".formatted(authentication.getName(), data));

        String userDn = authentication.getName();
        LocalDate fromLocalDate = LocalDate.parse(data.getFromDate(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        LocalDate toLocalDate = LocalDate.parse(data.getToDate(), DateTimeFormatter.ISO_ZONED_DATE_TIME);

        while(fromLocalDate.isBefore(toLocalDate)){

            GlobalCalendarRecord record = new GlobalCalendarRecord();
            record.setDate(fromLocalDate);
            record.setTitle(data.getTitle());
            record.setHoliday(Boolean.parseBoolean(data.getHoliday()));

            globalCalendarRecordRepository.save(record);

            fromLocalDate = fromLocalDate.plusDays(1);

        }

        return data;
    }

    @DeleteMapping("/delete/{eventId}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public void deleteEvent(Authentication authentication, @PathVariable String eventId){

        log.debug("deleteEvent called by {} with data: {}", authentication.getName(), eventId);

        globalCalendarRecordRepository.deleteById(Long.parseLong(eventId));
    }

    private List<CalendarEvent> globalCalendarRecordsToEvents(LocalDate fromDate, LocalDate toDate){
        List<CalendarEvent> events = new ArrayList<>();

        List<GlobalCalendarRecord> records = globalCalendarRecordRepository.findByDateBetween(fromDate, toDate);

        for(GlobalCalendarRecord record : records){
            CalendarEvent event = new CalendarEvent(record.getDate(), record.getTitle());
            event.setBackgroundColor(properties.getCalendarGlobalEventColor());
            event.setId(Long.toString(record.getId()));
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
