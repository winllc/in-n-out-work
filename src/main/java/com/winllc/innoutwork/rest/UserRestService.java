package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.data.web.PagedModel;
import org.springframework.ldap.filter.LikeFilter;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

import static com.winllc.innoutwork.service.LdapService.escapeLdapFilter;

@RestController
@RequestMapping("/api/users")
public class UserRestService {

    private static final Logger log = LoggerFactory.getLogger(UserRestService.class);

    private final LdapService ldapService;
    private final UserRecordRepository userRecordRepository;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final ApplicationProperties properties;
    private final UserService userService;

    public UserRestService(LdapService ldapService,
                           UserRecordRepository userRecordRepository, CheckInOutRecordRepository checkInOutRecordRepository,
                           ApplicationProperties properties, UserService userService) {
        this.ldapService = ldapService;
        this.userRecordRepository = userRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.userService = userService;
    }

    @GetMapping("/group/{groupName}")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER) or @permissionEvaluator.groupCheck(#groupName, #authentication)")
    public Map<String, Object> getUsers(HttpSession session, Authentication authentication,
                                        @PathVariable String groupName) {

        List<String> dns = ldapService.getGroupMembers(LdapDn.builder().dn(groupName).build());

        List<UserStatus> users = new ArrayList<>();
        for(String dn : dns){
            users.add(userService.getUserStatus(dn, session));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("data", users);

        return response;
    }

    /**
     * The signed-in user's direct reports. Scoped to the caller by design: it derives the manager
     * from the authenticated principal rather than a path variable, so it cannot be used to read
     * somebody else's team.
     */
    @GetMapping("/reports")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public Map<String, Object> getMyReports(HttpSession session, Authentication authentication) {
        List<UserStatus> reports = userService.getDirectReports(
                LdapDn.builder().dn(authentication.getName()).build(), session);

        Map<String, Object> response = new HashMap<>();
        response.put("data", reports);
        return response;
    }

    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER," +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
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
            // Escape LDAP special characters to prevent LDAP injection
            filter = "(&(objectclass=inetOrgPerson)(cn=*%s*))".formatted(escapeLdapFilter(search));
        }

        List<UserStatus> pageResult = ldapService.search(filter);
        List<UserStatus> users = new ArrayList<>();

        for(UserStatus user : pageResult){
            users.add(userService.getUserStatus(user.getDn(), session));
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




    @GetMapping("/managers/{dn}")
    public List<UserRecord> getManagersForUser(@PathVariable String dn){

        Optional<UserRecord> byDnIgnoreCase = userRecordRepository.findByDnIgnoreCase(dn);

        if(byDnIgnoreCase.isPresent()){
            UserRecord record = byDnIgnoreCase.get();
            List<String> altManagerList = record.getAltManagerList();
            if (altManagerList != null) {
                return altManagerList.stream().map(d -> {
                    UserRecord rec = new UserRecord();
                    rec.setDn(d);
                    return rec;
                }).toList();
            }
        }
        return Collections.emptyList();
    }

    @PostMapping("/managers/update")
    public void updateManagersForUser(Authentication authentication, @RequestBody List<String> managerDns){
        //todo implement
        log.info("Updating managers for user %s to: %s".formatted(authentication.getName(), managerDns));

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
        // Escape LDAP special characters to prevent LDAP injection
        String escapedSearch = escapeLdapFilter(search);
        LikeFilter likeFilter = new LikeFilter("cn", "*" + escapedSearch + "*");

        LdapQuery query = LdapQueryBuilder.query()
                        .base(properties.getUserBaseDn())
                                .filter(likeFilter);

        List<LdapUser> ldapUsers = ldapService.searchUsers(query);

        return ldapUsers;
    }



}
