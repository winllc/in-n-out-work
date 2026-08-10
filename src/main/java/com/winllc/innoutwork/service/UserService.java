package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.*;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.UserRestService;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRecordRepository userRecordRepository;
    private final LdapService ldapService;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;
    @Autowired
    private CheckInOutService checkInOutService;
    @Autowired
    private UserEventRecordRepository  userEventRecordRepository;

    public UserService(UserRecordRepository userRecordRepository,
                       LdapService ldapService, ApplicationProperties properties,
                       @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache) {
        this.userRecordRepository = userRecordRepository;
        this.ldapService = ldapService;
        this.properties = properties;
        this.userCache = userCache;
    }

    public Optional<UserRecord> getUserByDn(LdapDn dn) {
        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(dn.dn());
        if(recordOptional.isPresent()){
            return recordOptional;
        }else{
            Optional<LdapUser> userOptional = ldapService.lookupUser(dn);

            if(userOptional.isPresent()){
                LdapUser ldapUser = userOptional.get();
                UserRecord userRecord = new UserRecord(ldapUser);

                return Optional.of(userRecordRepository.save(userRecord));
            }

        }
        return Optional.empty();
    }

    public UserRecord updateProfile(Authentication authentication, ProfileForm form) {
        log.debug("Update Notes {}",  authentication.getName());
        UserRecord userRecord = new UserRecord();

        Optional<UserRecord> optionalRecord = userRecordRepository.findByDnIgnoreCase(authentication.getName());
        if(optionalRecord.isPresent()) {
            userRecord = optionalRecord.get();
        }else{
            userRecord.setDn(authentication.getName());
        }

        userRecord.setNotes(form.getNotes());
        if(StringUtils.isNotBlank(form.getLoginTime())) {
            userRecord.setChosenLoginTime(LocalTime.parse(form.getLoginTime(), DateTimeFormatter.ISO_TIME));
        }

        return userRecordRepository.save(userRecord);
    }

    public UserRecord updateRole(LdapDn dn, UserRoleEnum role) {
        UserRecord userRecord = new UserRecord();

        Optional<UserRecord> optionalRecord = userRecordRepository.findByDnIgnoreCase(dn.dn());
        if(optionalRecord.isPresent()) {
            userRecord = optionalRecord.get();
        }else{
            userRecord.setDn(dn.dn());
        }

        userRecord.setUserRole(role);

        return userRecordRepository.save(userRecord);
    }

    public UserRecord updateGroupFavorite(Authentication authentication, GroupFavorite groupFavorite) {
        log.debug("Update favorite groups {}: {}",  authentication.getName(), groupFavorite);
        UserRecord userRecord = new UserRecord();

        Optional<UserRecord> optionalRecord = userRecordRepository.findByDnIgnoreCase(authentication.getName());
        if(optionalRecord.isPresent()) {
            userRecord = optionalRecord.get();
        }else{
            userRecord.setDn(authentication.getName());
        }

        if(groupFavorite.isSelected()){
            userRecord.addGroup(groupFavorite.getGroupDn());
        }else{
            userRecord.removeGroup(groupFavorite.getGroupDn());
        }

        return userRecordRepository.save(userRecord);
    }

    public UserStatus getUserStatus(String dn, HttpSession session){
        UserStatus status = UserStatus.builder()
                .dn(dn).build();

        ZonedDateTime selectedDate = CheckInOutService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);

        List<CheckInOutRecord> todaysRecordsForUser = checkInOutService.findRecordsForUser(dn, session);
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
            status.setLastStatusChangeAt(record.getZonedDateTimestamp());
            firstLogin.ifPresent(r -> status.setCheckedInAt(r.getZonedDateTimestamp()));
            lastLogout.ifPresent(r -> status.setCheckedOutAt(r.getZonedDateTimestamp()));

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
                .stream()
                .filter(r -> r.getStatus() != UserStatusEnum.STANDARD)
                .findFirst()
                .ifPresent(userEventRecord -> {
                    status.setStatus(userEventRecord.getStatus().name());
                });

        return status;
    }

    public UserStatus getUserDetails(LdapDn dn, HttpSession session) {
        String ldapDn = dn.dn();
        UserStatus.UserStatusBuilder builder = UserStatus.builder();

        builder.dn(ldapDn);

        Optional<UserRecord> userByDn = getUserByDn(dn);
        if(userByDn.isPresent()) {
            UserRecord userRecord = userByDn.get();
            if(userRecord.getUserRole() != null) {
                builder.role(userRecord.getUserRole().name());
            }else{
                builder.role(UserRoleEnum.USER.name());
            }
            builder.notes(userRecord.getNotes());
            LocalTime averageLogin = userRecord.getAverageLoginTime();
            if(averageLogin != null) {
                builder.averageLoginTime(DateTimeConstants.TIME_FORMATTER.withZone(ZoneId.systemDefault()).format(averageLogin));
            }
        }

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(ldapDn);
        builder.memberOf(groupsForUser);

        UserStatus userStatus = getUserStatus(ldapDn, session);
        builder.status(userStatus.getStatus());
        builder.organization(userStatus.getOrganization());
        builder.employeeType(userStatus.getEmployeeType());
        builder.location(userStatus.getLocation());

        LdapUser userManager = getUserManager(dn);

        if(userManager != null) {
            builder.managerDn(userManager.getDn());
        }

        return builder.build();
    }

    public LdapUser getUserManager(LdapDn userDn){
        Optional<LdapUser> userOptional = ldapService.lookupUser(userDn);
        if(userOptional.isPresent()) {
            LdapUser ldapUser = userOptional.get();
            if(ldapUser.getManagerId() != null){
                Optional<LdapUser> managerOptional = ldapService.lookupUser(
                        properties.getManagerLdapIdAttribute(), ldapUser.getManagerId());
                if(managerOptional.isPresent()){
                    return managerOptional.get();
                }
            }
        }

        return null;
    }

    public UserRecord createUserIfDoesNotExist(LdapDn dn) {
        LdapUser ldapUser = userCache.get(dn.dn());

        Optional<UserRecord> byDnIgnoreCase = userRecordRepository.findByDnIgnoreCase(dn.toString());
        if(byDnIgnoreCase.isEmpty()){
            UserRecord userRecord = UserRecord.builder()
                    .dn(dn.toString())
                    .employeeType(ldapUser.getEmployeeType())
                    .organization(ldapUser.getOrganization())
                    .location(ldapUser.getLocation())
                    .branch(ldapUser.getBranch())
                    .dutySubOrganization(ldapUser.getDutySubOrganization())
                    .userRole(UserRoleEnum.USER)
                    .build();
            return userRecordRepository.save(userRecord);
        }else{
            UserRecord userRecord = byDnIgnoreCase.get();
            boolean updated = false;
            if(!Objects.equals(ldapUser.getEmployeeType(), userRecord.getEmployeeType())){
                userRecord.setEmployeeType(ldapUser.getEmployeeType());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getOrganization(), userRecord.getOrganization())){
                userRecord.setOrganization(ldapUser.getOrganization());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getLocation(), userRecord.getLocation())){
                userRecord.setLocation(ldapUser.getLocation());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getBranch(), userRecord.getBranch())){
                userRecord.setBranch(ldapUser.getBranch());
                updated = true;
            }
            if(!Objects.equals(ldapUser.getDutySubOrganization(), userRecord.getDutySubOrganization())){
                userRecord.setDutySubOrganization(ldapUser.getDutySubOrganization());
                updated = true;
            }

            if(updated){
                userRecord = userRecordRepository.save(userRecord);
            }

            return userRecord;
        }
    }
}
