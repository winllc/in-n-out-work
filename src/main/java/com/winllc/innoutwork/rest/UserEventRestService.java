package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.SystemDateTimeForm;
import com.winllc.innoutwork.data.UserEventData;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import io.micrometer.common.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RequestMapping("/api/event")
@RestController
public class UserEventRestService {

    private final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private UserEventRecordRepository userEventRecordRepository;

    Map<LocalDate, UserEventData> userEventRecordMap = new HashMap<>();

    @PostMapping("/day")
    public UserEventData getEventForDay(Authentication authentication,
                                        @RequestBody UserEventData data){

        LocalDate localDate = LocalDate.parse(data.getDate(), dtf);

        String userDn = authentication.getName();
        if(StringUtils.isNotBlank(data.getDn())){
            userDn = data.getDn();
        }

        UserEventRecord record = null;

        Optional<UserEventRecord> byDnAndDate = userEventRecordRepository.findByDnAndDate(userDn, localDate);
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

    @PostMapping("/update")
    public UserEventData updateEventForDay(Authentication authentication,
                                             @RequestBody UserEventData data){

        String userDn = authentication.getName();
        LocalDate localDate = LocalDate.parse(data.getDate(), dtf);

        userEventRecordMap.put(localDate, data);

        UserEventRecord record = new UserEventRecord();
        record.setDn(userDn);
        record.setDate(localDate);

        Optional<UserEventRecord> byDnAndDate = userEventRecordRepository.findByDnAndDate(userDn, localDate);
        if(byDnAndDate.isPresent()){
            record = byDnAndDate.get();
        }

        record.setStatus(UserStatusEnum.valueOf(data.getStatus()));

        userEventRecordRepository.save(record);

        return data;
    }
}
