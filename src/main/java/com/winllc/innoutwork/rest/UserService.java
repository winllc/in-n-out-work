package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.DatabaseService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserService {

    @Autowired
    private LdapService ldapService;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private UserRecordRepository userRecordRepository;

    @GetMapping("/{groupName}")
    public Map<String, Object> getUsers(
            @PathVariable String groupName) {

        List<String> dns = ldapService.getGroupMembers(groupName);

        List<UserStatus> users = new ArrayList<>();
        for(String dn : dns){
            UserStatus status = UserStatus.builder()
                    .dn(dn).build();

            List<CheckInOutRecord> todaysRecordsForUser = databaseService.findTodaysRecordsForUser(dn);
            if(todaysRecordsForUser != null && !todaysRecordsForUser.isEmpty()){
                todaysRecordsForUser.stream()
                        .filter(r -> r.getAction() == CheckInOutEnum.CHECK_IN)
                        .findFirst()
                        .ifPresent(r -> status.setCheckedInAt(r.getTimestamp()));

                todaysRecordsForUser.stream()
                        .filter(r -> r.getAction() == CheckInOutEnum.CHECK_OUT)
                        .findFirst()
                        .ifPresent(r -> status.setCheckedOutAt(r.getTimestamp()));

                if(status.getCheckedInAt() != null){
                    status.setCheckedIn(true);
                    if(status.getCheckedOutAt() != null){
                        if(status.getCheckedOutAt().isAfter(status.getCheckedInAt())){
                            status.setCheckedIn(false);
                        }
                    }
                }

                 } else {
                status.setCheckedIn(false);
            }
            users.add(status);
        }

        users.forEach(u -> {
            Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(u.getDn());
            if(recordOptional.isPresent()){
                u.setNotes(recordOptional.get().getNotes());
            }
        });


        Map<String, Object> response = new HashMap<>();
        response.put("data", users);

        return response;
    }

}
