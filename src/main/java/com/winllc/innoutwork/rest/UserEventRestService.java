package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.SystemDateTimeForm;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.service.ReportService;
import io.micrometer.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@RequestMapping("/api/event")
@RestController
public class UserEventRestService {

    private static final Logger log = LoggerFactory.getLogger(UserEventRestService.class);

    private final UserEventRecordRepository userEventRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final ApplicationProperties properties;

    public UserEventRestService(UserEventRecordRepository userEventRecordRepository,
                                CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties) {
        this.userEventRecordRepository = userEventRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
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

        List<CalendarEvent> events = byDnAndDateBetween.stream()
                .map(r -> {
                    CalendarEvent event = new CalendarEvent(r.getDate(), r.getStatus().getFriendlyName());

                    if(r.getStatus().isExcusable()){
                        event.setBackgroundColor(properties.getCalendarStatusEventColor());
                    }else{
                        event.setBackgroundColor(properties.getCalendarAbsentStatusEventColor());
                    }

                    return event;
                })
                .toList();

        return events;
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
        List<CalendarEvent> calendarEvents = auditRecordsToEvents(dn, fromDate, toDate);

        allEvents.addAll(events);
        allEvents.addAll(calendarEvents);

        return allEvents;
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
