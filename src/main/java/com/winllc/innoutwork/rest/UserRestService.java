package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import org.springframework.beans.factory.annotation.Autowired;
import com.winllc.innoutwork.license.LicensedFeature;
import com.winllc.innoutwork.license.RequiresFeature;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;
import org.springframework.data.web.PagedModel;
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
    private final AccessPolicy accessPolicy;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    public UserRestService(LdapService ldapService,
                           UserRecordRepository userRecordRepository, CheckInOutRecordRepository checkInOutRecordRepository,
                           ApplicationProperties properties, UserService userService, AccessPolicy accessPolicy) {
        this.ldapService = ldapService;
        this.userRecordRepository = userRecordRepository;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
        this.userService = userService;
        this.accessPolicy = accessPolicy;
    }

    /** A group's members: open to its members, its managers and ADMIN; details only of those the viewer may see. */
    @GetMapping("/group/{groupName}")
    @PreAuthorize("@accessPolicy.canViewGroup(authentication, #groupName)")
    public Map<String, Object> getUsers(HttpSession session, Authentication authentication,
                                        @PathVariable String groupName) {

        List<String> dns = ldapService.getGroupMembers(LdapDn.builder().dn(groupName).build());

        List<UserStatus> users = accessPolicy.scope(authentication).show(userService.getUserStatuses(dns, session));

        Map<String, Object> response = new HashMap<>();
        response.put("data", users);

        return response;
    }

    /**
     * The signed-in user's direct reports. Scoped to the caller by design: it derives the manager
     * from the authenticated principal rather than a path variable, so it cannot be used to read
     * somebody else's team.
     */
    @RequiresFeature(LicensedFeature.METRICS)
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

    /**
     * Find a coworker: anyone may find anyone and see whether they are in. Times, notes and the rest only for the
     * people the viewer may see the details of (AccessPolicy).
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).USER," +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public List<UserStatus> searchUsers(
            HttpSession session,
            Authentication authentication,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "asc") String dir) {

        page--;

        Pageable pageable = PageRequest.of(page, size,
                dir.equalsIgnoreCase("asc") ? Sort.by(sort).ascending() : Sort.by(sort).descending());

        // Already parenthesised by ApplicationProperties, which JNDI requires - it rejects a
        // bare "attr=value" as a malformed search filter.
        String filter = properties.getUserLdapFilter();

        if (!search.isEmpty()) {
            // Escape LDAP special characters to prevent LDAP injection
            filter = "(&%s(cn=*%s*))".formatted(filter, escapeLdapFilter(search));
        }

        List<UserStatus> pageResult = ldapService.search(filter);
        List<UserStatus> users = userService.getUserStatuses(pageResult.stream().map(UserStatus::getDn).toList(), session);

        //PagedModel<UserStatus> response = new PagedModel<>(pageResult);
        return accessPolicy.scope(authentication).show(users);
    }

    /** A person's check-in history: theirs, and the people they are responsible for (AccessPolicy). */
    @RequiresFeature(LicensedFeature.ATTENDANCE)
    @GetMapping("/checkinoutrecords/{dn}")
    @PreAuthorize("@accessPolicy.canViewDetails(authentication, #dn)")
    public PagedModel<CheckInOutRecord> getUserCheckInOutRecords(
            Authentication authentication,
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




    /** Who receives a person's absence alerts besides their manager: as their details (AccessPolicy). */
    @GetMapping("/managers/{dn}")
    @PreAuthorize("@accessPolicy.canViewDetails(authentication, #dn)")
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
            String old = userRecord.getAlternateManagers();

            userRecord.setAlternateManagers("");
            for(String managerDn : managerDns){
                userRecord.addAltManager(managerDn);
            }

            // Who receives this person's absence alerts: saved and audited together.
            audit.inTransaction(() -> {
                UserRecord saved = userRecordRepository.save(userRecord);
                audit.record(AuditEntry.of(AuditEntry.Category.DATA, "person.updated")
                        .target("user", saved.getDn(), LdapDn.cnOf(saved.getDn()))
                        .detail("via", "alternate-managers")
                        .change("alternateManagers", old, saved.getAlternateManagers()));
            });
        }
    }


    @GetMapping("/usersearch")
    public List<LdapUser> searchUsers(@RequestParam String search) {
        // escapeLdapFilter is the only escaping applied: LikeFilter encodes the value again, so a
        // pre-escaped term reached the server as \5c-prefixed text and a name holding a bracket
        // matched nothing. A raw filter also keeps a typed asterisk literal, as /search does.
        LdapQuery query = LdapQueryBuilder.query()
                .base(properties.getUserBaseDn())
                .filter("(cn=*%s*)".formatted(escapeLdapFilter(search)));

        List<LdapUser> ldapUsers = ldapService.searchUsers(query);

        return ldapUsers;
    }



}
