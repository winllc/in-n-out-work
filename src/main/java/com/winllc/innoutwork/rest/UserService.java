package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.DatabaseService;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.web.PagedModel;
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
    @Autowired
    private CheckInOutRecordRepository checkInOutRecordRepository;
    @Autowired
    private ApplicationProperties properties;

    @GetMapping("/group/{groupName}")
    public Map<String, Object> getUsers(
            @PathVariable String groupName) {

        List<String> dns = ldapService.getGroupMembers(groupName);

        List<UserStatus> users = new ArrayList<>();
        for(String dn : dns){
            users.add(getUserStatus(dn));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("data", users);

        return response;
    }

    @GetMapping("/search")
    public List<UserStatus> searchUsers(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "asc") String dir) {

        page--;

        Pageable pageable = PageRequest.of(page, size,
                dir.equalsIgnoreCase("asc") ? Sort.by(sort).ascending() : Sort.by(sort).descending());

        String filter = "objectClass=inetOrgPerson";

        if (!search.isEmpty()) {
            filter = "(&(objectclass=inetOrgPerson)(cn=*%s*))".formatted(search);
        }

        List<UserStatus> pageResult = ldapService.search(properties.getBaseDn(), filter, page, size);
        List<UserStatus> users = new ArrayList<>();

        for(UserStatus user : pageResult){
            users.add(getUserStatus(user.getDn()));
        }

        //PagedModel<UserStatus> response = new PagedModel<>(pageResult);
        return users;
    }

    @GetMapping("/checkinoutrecords/{dn}")
    public PagedModel<CheckInOutRecord> getUserCheckInOutRecords(
            @PathVariable String dn,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "timestamp") String sort,
            @RequestParam(defaultValue = "desc") String dir) {

        page--;

        Pageable pageable = PageRequest.of(page, size,
                dir.equalsIgnoreCase("asc") ? Sort.by(sort).ascending() : Sort.by(sort).descending());

        ZonedDateTime beginning = ZonedDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        Page<CheckInOutRecord> records = checkInOutRecordRepository.findByDnIgnoreCaseOrderByTimestampDesc(pageable, dn);

        PagedModel<CheckInOutRecord> response = new PagedModel<>(new PageImpl<>(records.getContent(), pageable, records.getTotalElements()));
        return response;
    }

    private UserStatus getUserStatus(String dn){
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

        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(status.getDn());
        if(recordOptional.isPresent()){
            status.setNotes(recordOptional.get().getNotes());
        }

        return status;
    }

}
