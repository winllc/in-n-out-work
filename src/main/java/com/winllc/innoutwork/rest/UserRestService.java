package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.DatabaseService;
import com.winllc.innoutwork.service.LdapGroupLoader;
import com.winllc.innoutwork.service.LdapService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.data.web.PagedModel;
import org.springframework.ldap.filter.LikeFilter;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.xml.crypto.Data;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/users")
public class UserRestService {

    private static final Logger log = LoggerFactory.getLogger(UserRestService.class);

    private final LdapService ldapService;
    private final DatabaseService databaseService;
    private final UserRecordRepository userRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final ApplicationProperties properties;
    private final UserEventRecordRepository userEventRecordRepository;

    public UserRestService(LdapService ldapService, DatabaseService databaseService,
                           UserRecordRepository userRecordRepository, CheckInOutRecordRepository checkInOutRecordRepository,
                           ApplicationProperties properties, UserEventRecordRepository userEventRecordRepository) {
        this.ldapService = ldapService;
        this.databaseService = databaseService;
        this.userRecordRepository = userRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.userEventRecordRepository = userEventRecordRepository;
    }

    @GetMapping("/group/{groupName}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER) or @permissionEvaluator.groupCheck(#groupName, #authentication)")
    public Map<String, Object> getUsers(HttpSession session, Authentication authentication,
                                        @PathVariable String groupName) {

        List<String> dns = ldapService.getGroupMembers(LdapDn.builder().dn(groupName).build());

        List<UserStatus> users = new ArrayList<>();
        for(String dn : dns){
            users.add(getUserStatus(dn, session));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("data", users);

        return response;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public List<UserStatus> searchUsers(
            HttpSession session,
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

        List<UserStatus> pageResult = ldapService.search(filter, page, size);
        List<UserStatus> users = new ArrayList<>();

        for(UserStatus user : pageResult){
            users.add(getUserStatus(user.getDn(), session));
        }

        //PagedModel<UserStatus> response = new PagedModel<>(pageResult);
        return users;
    }

    @GetMapping("/checkinoutrecords/{dn}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public PagedModel<CheckInOutRecord> getUserCheckInOutRecords(
            @PathVariable String dn,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "timestamp") String sort,
            @RequestParam(defaultValue = "desc") String dir) {

        page--;

        Pageable pageable = PageRequest.of(page, size,
                dir.equalsIgnoreCase("asc") ? Sort.by(sort).ascending() : Sort.by(sort).descending());

        Page<CheckInOutRecord> records = checkInOutRecordRepository.findByDnIgnoreCaseOrderByTimestampDesc(pageable, dn);

        return new PagedModel<>(new PageImpl<>(records.getContent(), pageable, records.getTotalElements()));
    }

    public UserStatus getUserStatus(String dn, HttpSession session){
        UserStatus status = UserStatus.builder()
                .dn(dn).build();

        ZonedDateTime selectedDate = DatabaseService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);

        List<CheckInOutRecord> todaysRecordsForUser = databaseService.findRecordsForUser(dn, session);
        if(todaysRecordsForUser != null && !todaysRecordsForUser.isEmpty()){

            Optional<CheckInOutRecord> mostRecent = todaysRecordsForUser.stream()
                    .sorted()
                    .findFirst();

            Optional<CheckInOutRecord> firstLogin = todaysRecordsForUser.stream()
                    .sorted(Comparator.reverseOrder())
                    .filter(r -> r.getAction() == CheckInOutEnum.CHECK_IN)
                    .findFirst();

            Optional<CheckInOutRecord> lastLogout = todaysRecordsForUser.stream()
                    .sorted()
                    .filter(r -> r.getAction() == CheckInOutEnum.CHECK_OUT)
                    .findFirst();

            CheckInOutRecord record = mostRecent.get();
            status.setLastStatusChangeAt(record.getTimestamp());
            firstLogin.ifPresent(r -> status.setCheckedInAt(r.getTimestamp()));
            lastLogout.ifPresent(r -> status.setCheckedOutAt(r.getTimestamp()));

            if(record.getAction() == CheckInOutEnum.CHECK_IN ||  record.getAction() == CheckInOutEnum.UNLOCK){
                status.setStatus("IN");
            }else if(record.getAction() == CheckInOutEnum.CHECK_OUT){
                status.setStatus("OUT");
            }else if(record.getAction() == CheckInOutEnum.LOCK){
                status.setStatus("AWAY");
            }
        } else {
            status.setStatus("NONE");
        }

        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(status.getDn());
        if(recordOptional.isPresent()){
            UserRecord record = recordOptional.get();
            status.setNotes(record.getNotes());
            status.setOrganization(record.getOrganization());
            status.setEmployeeType(record.getEmployeeType());
        }

        userEventRecordRepository.findByDnIgnoreCaseAndDate(dn, selectedDate.toLocalDate())
                .ifPresent(userEventRecord -> {
                    if(userEventRecord.getStatus() != UserStatusEnum.STANDARD) {
                        status.setStatus(userEventRecord.getStatus().name());
                    }
                });

        return status;
    }


    @GetMapping("/managers/{dn}")
    public List<UserRecord> getManagersForUser(@PathVariable String dn){

        Optional<UserRecord> byDnIgnoreCase = userRecordRepository.findByDnIgnoreCase(dn);

        if(byDnIgnoreCase.isPresent()){
            UserRecord record = byDnIgnoreCase.get();
            return record.getAltManagerList().stream().map(d -> {
                UserRecord rec = new UserRecord();
                rec.setDn(d);
                return rec;
            }).toList();
        }else{
            return Collections.emptyList();
        }
    }

    @PostMapping("/managers/update")
    public void updateManagersForUser(Authentication authentication, @RequestBody List<String> managerDns){
        //todo implement
        log.info("updateManagersForUser");

        Optional<UserRecord> byDnIgnoreCase = userRecordRepository.findByDnIgnoreCase(authentication.getName());

        if(byDnIgnoreCase.isPresent()){
            UserRecord userRecord = byDnIgnoreCase.get();

            userRecord.setAlternateManagers("");
            for(String managerDn : managerDns){
                userRecord.addAltManager(managerDn);
            }

            userRecordRepository.save(userRecord);
        }
    }


    @GetMapping("/usersearch")
    public List<LdapUser> searchUsers(@RequestParam String search) {

        LikeFilter likeFilter = new LikeFilter("cn", "*" + search + "*");

        LdapQuery query = LdapQueryBuilder.query()
                        .base(properties.getUserBaseDn())
                                .filter(likeFilter);

        List<LdapUser> ldapUsers = ldapService.searchUsers(query);

        return ldapUsers;
    }

}
