package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.util.LogSafe;
import org.springframework.beans.factory.annotation.Autowired;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.data.CalendarEventData;
import com.winllc.innoutwork.data.EventRange;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.GlobalCalendarRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiresFeature(LicensedFeature.CALENDAR)
@RequestMapping("/api/settings/calendar")
@RestController
public class SettingsRestService {

    private static final Logger log = LoggerFactory.getLogger(SettingsRestService.class);

    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final GlobalCalendarRecordRepository globalCalendarRecordRepository;
    private final ApplicationProperties properties;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    public SettingsRestService(CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties,
                               GlobalCalendarRecordRepository globalCalendarRecordRepository) {
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

        log.debug("updateCalendar called by {} with data: {}", authentication.getName(), LogSafe.of(String.valueOf(data)));

        // At most application.events.max-range-days, like every API that creates events; refused with 400 otherwise.
        EventRange range = EventRange.parse(data.getFromDate(), data.getToDate(),
                properties.getEvents().getMaxRangeDays());
        LocalDate firstDay = range.from();
        LocalDate endDay = range.toExclusive();

        // The organization calendar is configuration everyone's absence checks read: saved and audited together.
        audit.inTransaction(() -> {
            List<String> ids = new ArrayList<>();
            LocalDate day = firstDay;
            while(day.isBefore(endDay)){

                GlobalCalendarRecord record = new GlobalCalendarRecord();
                record.setDate(day);
                record.setTitle(data.getTitle());
                record.setHoliday(Boolean.parseBoolean(data.getHoliday()));

                ids.add(String.valueOf(globalCalendarRecordRepository.save(record).getId()));

                day = day.plusDays(1);

            }
            audit.record(AuditEntry.of(AuditEntry.Category.CONFIGURATION, "calendar.org-event-created")
                    .target("calendar-event", String.join(",", ids), data.getTitle())
                    .detail("title", data.getTitle())
                    .detail("holiday", Boolean.parseBoolean(data.getHoliday()))
                    .detail("from", firstDay.toString())
                    .detail("to", endDay.toString())
                    .detail("days", ids.size()));
        });

        return data;
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<Map<String, String>> invalid(InvalidRequestException e) {
        return ApiErrors.of(e);
    }

    @DeleteMapping("/delete/{eventId}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public void deleteEvent(Authentication authentication, @PathVariable String eventId){

        log.debug("deleteEvent called by {} with data: {}", authentication.getName(), eventId);

        long id = Long.parseLong(eventId);
        audit.inTransaction(() -> {
            GlobalCalendarRecord old = globalCalendarRecordRepository.findById(id).orElse(null);
            globalCalendarRecordRepository.deleteById(id);
            audit.record(AuditEntry.of(AuditEntry.Category.CONFIGURATION, "calendar.org-event-deleted")
                    .target("calendar-event", eventId, old == null ? null : old.getTitle())
                    .detail("title", old == null ? null : old.getTitle())
                    .detail("date", old == null || old.getDate() == null ? null : old.getDate().toString())
                    .detail("holiday", old == null ? null : old.isHoliday()));
        });
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
