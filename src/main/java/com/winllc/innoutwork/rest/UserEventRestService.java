package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.CalendarEvent;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.SystemDateTimeForm;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import io.micrometer.common.util.StringUtils;
import org.checkerframework.checker.units.qual.C;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequestMapping("/api/event")
@RestController
public class UserEventRestService {

    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserEventRecordRepository userEventRecordRepository;

    public UserEventRestService(UserEventRecordRepository userEventRecordRepository) {
        this.userEventRecordRepository = userEventRecordRepository;
    }

    @PostMapping("/day")
    public UserEventData getEventForDay(Authentication authentication,
                                        @RequestBody UserEventData data){

        LocalDate localDate = LocalDate.parse(data.getDate(), dtf);

        String userDn = authentication.getName();
        if(StringUtils.isNotBlank(data.getDn())){
            userDn = data.getDn();
        }

        UserEventRecord record = null;

        Optional<UserEventRecord> byDnAndDate = userEventRecordRepository.findByDnIgnoreCaseAndDate(userDn, localDate);
        if(byDnAndDate.isPresent()){
            record = byDnAndDate.get();
        }

        UserEventData eventData = new UserEventData();
        eventData.setDn(userDn);
        eventData.setDate(dtf.format(localDate));

        if(record == null){
            eventData.setStatus(UserStatusEnum.STANDARD.name());
        }else{
            eventData.setStatus(record.getStatus().name());
        }

        return eventData;
        /*
        if(userEventRecordMap.containsKey(localDate)){
            return userEventRecordMap.get(localDate);
        }else{
            record = new UserEventData();
            record.setStatus(UserStatusEnum.STANDARD.name());
            return record;
        }

         */
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
                    CalendarEvent event = new CalendarEvent();
                    event.setId(r.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    event.setTitle(r.getStatus().getFriendlyName());
                    event.setStart(r.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    return event;
                })
                .toList();

        return events;
    }

    @PostMapping("/update")
    public UserEventData updateEventForDay(Authentication authentication,
                                             @RequestBody UserEventData data){

        String userDn = authentication.getName();
        LocalDate fromLocalDate = LocalDate.parse(data.getFromDate(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        LocalDate toLocalDate = LocalDate.parse(data.getToDate(), DateTimeFormatter.ISO_ZONED_DATE_TIME);

        while(fromLocalDate.isBefore(toLocalDate)){
            UserEventRecord record = new UserEventRecord();
            record.setDn(userDn);
            record.setDate(fromLocalDate);

            Optional<UserEventRecord> byDnAndDate = userEventRecordRepository.findByDnIgnoreCaseAndDate(userDn, fromLocalDate);
            if(byDnAndDate.isPresent()){
                record = byDnAndDate.get();
            }

            record.setStatus(UserStatusEnum.valueOf(data.getStatus()));

            userEventRecordRepository.save(record);

            fromLocalDate = fromLocalDate.plusDays(1);

        }

        //todo handle range update
        /*
        UserEventRecord record = new UserEventRecord();
        record.setDn(userDn);
        record.setDate(localDate);

        Optional<UserEventRecord> byDnAndDate = userEventRecordRepository.findByDnIgnoreCaseAndDate(userDn, localDate);
        if(byDnAndDate.isPresent()){
            record = byDnAndDate.get();
        }

        record.setStatus(UserStatusEnum.valueOf(data.getStatus()));

        userEventRecordRepository.save(record);

         */

        return data;
    }
}
