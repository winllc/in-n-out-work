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
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRecordRepository userRecordRepository;
    private final LdapService ldapService;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;
    private final CheckInOutService checkInOutService;
    private final UserEventRecordRepository  userEventRecordRepository;

    public UserService(UserRecordRepository userRecordRepository,
                       LdapService ldapService, ApplicationProperties properties,
                       @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache,
                       CheckInOutService checkInOutService, UserEventRecordRepository userEventRecordRepository) {
        this.userRecordRepository = userRecordRepository;
        this.ldapService = ldapService;
        this.properties = properties;
        this.userCache = userCache;
        this.checkInOutService = checkInOutService;
        this.userEventRecordRepository = userEventRecordRepository;
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

    /**
     * Returns the users who report directly to {@code managerDn}, each enriched with today's
     * attendance status so they can be listed in one table.
     * <p>
     * Reporting comes from the directory, not from the application role: a user is a manager
     * because other entries point at their id, so someone holding only {@code USER} can still
     * have reports. A manager with no reports yields an empty list.
     *
     * @return the direct reports sorted by common name, never {@code null}
     */
    public List<UserStatus> getDirectReports(LdapDn managerDn, HttpSession session) {
        Optional<LdapUser> managerOptional = ldapService.lookupUser(managerDn);
        if (managerOptional.isEmpty()) {
            log.debug("No directory entry for {}, so no reports", managerDn.dn());
            return List.of();
        }

        String managerId = managerOptional.get().getManagerLdapId();
        if (StringUtils.isBlank(managerId)) {
            log.debug("{} carries no manager id, so nobody reports to them", managerDn.dn());
            return List.of();
        }

        List<LdapUser> reports = ldapService.findUsersReportingTo(managerId);
        log.debug("{} (manager id {}) has {} direct report(s)", managerDn.dn(), managerId, reports.size());

        return reports.stream()
                .filter(Objects::nonNull)
                .filter(u -> StringUtils.isNotBlank(u.getDn()))
                // A manager whose own entry somehow points at their id must not list themselves.
                .filter(u -> !u.getDn().equalsIgnoreCase(managerDn.dn()))
                .map(u -> describeReport(u, session))
                .sorted(Comparator.comparing(UserStatus::getCn, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Builds the table row for one report: today's status, backfilled from the directory entry we
     * already hold.
     * <p>
     * {@link #getUserStatus} reads the descriptive fields from the local {@code UserRecord}, which
     * is only created once the application has seen that user check in. Reports who have never used
     * the app therefore have no record yet, and those columns would otherwise all be blank even
     * though the directory knows the values.
     */
    private UserStatus describeReport(LdapUser ldapUser, HttpSession session) {
        UserStatus status = getUserStatus(ldapUser.getDn(), session);

        if (StringUtils.isBlank(status.getOrganization())) {
            status.setOrganization(ldapUser.getOrganization());
        }
        if (StringUtils.isBlank(status.getEmployeeType())) {
            status.setEmployeeType(ldapUser.getEmployeeType());
        }
        if (StringUtils.isBlank(status.getLocation())) {
            status.setLocation(ldapUser.getLocation());
        }
        if (StringUtils.isBlank(status.getEmail())) {
            status.setEmail(ldapUser.getEmail());
        }

        return status;
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

    /**
     * Number of users held in memory per round trip. Each batch costs one select and one
     * batched write, so this trades memory against query count; 500 keeps both small.
     */
    static final int SYNC_BATCH_SIZE = 500;

    /**
     * Refreshes the metadata columns on {@link UserRecord} from the directory.
     *
     * <p>Cost is bounded regardless of directory size: one LDAP search for the whole
     * population, then per batch of {@value #SYNC_BATCH_SIZE} users one select by DN and
     * one batched save of just the rows that actually changed. Comparing before writing is
     * what keeps a steady-state run to zero writes - the common case, since directory
     * metadata rarely moves.
     *
     * <p>Only directory-owned columns are touched. Notes, favourites, role, alternate
     * managers and login times are set inside the app and are never overwritten here.
     *
     * @return counts describing what the run did
     */
    public DirectorySyncResult syncUserRecordsFromDirectory() {
        long start = System.currentTimeMillis();

        List<LdapUser> directoryUsers = ldapService.findAllUsers();
        if (directoryUsers.isEmpty()) {
            log.debug("Directory returned no users; nothing to refresh");
            return DirectorySyncResult.EMPTY;
        }

        // One entry per DN: a duplicate would otherwise be inserted twice in the same batch.
        Map<String, LdapUser> byDn = new LinkedHashMap<>();
        int skipped = 0;
        for (LdapUser user : directoryUsers) {
            if (user == null || StringUtils.isBlank(user.getDn())) {
                skipped++;
                continue;
            }
            byDn.put(user.getDn().toLowerCase(), user);
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        List<LdapUser> batch = new ArrayList<>(SYNC_BATCH_SIZE);
        for (LdapUser user : byDn.values()) {
            batch.add(user);

            if (batch.size() == SYNC_BATCH_SIZE) {
                int[] counts = syncBatch(batch);
                created += counts[0];
                updated += counts[1];
                unchanged += counts[2];
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            int[] counts = syncBatch(batch);
            created += counts[0];
            updated += counts[1];
            unchanged += counts[2];
        }

        DirectorySyncResult result =
                new DirectorySyncResult(directoryUsers.size(), created, updated, unchanged, skipped);

        if (result.written() > 0) {
            log.info("Directory refresh: {} scanned, {} created, {} updated, {} unchanged, {} skipped in {}ms",
                    result.scanned(), result.created(), result.updated(), result.unchanged(), result.skipped(),
                    System.currentTimeMillis() - start);
        } else {
            log.debug("Directory refresh: {} scanned, no changes, in {}ms",
                    result.scanned(), System.currentTimeMillis() - start);
        }

        return result;
    }

    /**
     * Handles one batch: a single select for the DNs, then a single save of the subset
     * that changed.
     *
     * @return {created, updated, unchanged}
     */
    private int[] syncBatch(List<LdapUser> batch) {
        List<String> dns = batch.stream()
                .map(u -> u.getDn().toLowerCase())
                .toList();

        Map<String, UserRecord> existing = new HashMap<>();
        for (UserRecord record : userRecordRepository.findAllByLowercaseDnIn(dns)) {
            existing.put(record.getDn().toLowerCase(), record);
        }

        List<UserRecord> toSave = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (LdapUser user : batch) {
            UserRecord record = existing.get(user.getDn().toLowerCase());

            if (record == null) {
                UserRecord fresh = UserRecord.builder()
                        .dn(user.getDn())
                        .userRole(UserRoleEnum.USER)
                        .build();
                applyDirectoryMetadata(user, fresh);
                toSave.add(fresh);
                created++;
                log.debug("Directory refresh: creating record for {}", user.getDn());
            } else if (applyDirectoryMetadata(user, record)) {
                toSave.add(record);
                updated++;
            } else {
                unchanged++;
            }
        }

        if (!toSave.isEmpty()) {
            userRecordRepository.saveAll(toSave);
        }

        return new int[]{created, updated, unchanged};
    }

    /**
     * Copies the directory-owned metadata onto a record, reporting whether anything moved.
     *
     * <p>Blank incoming values are ignored rather than written: the attribute mappers log
     * and skip on a mapping failure, which surfaces here as a null, and a transient failure
     * must not blank a column that still holds good data. A value genuinely removed in the
     * directory therefore needs clearing by hand.
     *
     * @return true when at least one column changed
     */
    static boolean applyDirectoryMetadata(LdapUser user, UserRecord record) {
        boolean changed = false;

        changed |= copyIfPresent(user.getOrganization(), record.getOrganization(), record::setOrganization);
        changed |= copyIfPresent(user.getEmployeeType(), record.getEmployeeType(), record::setEmployeeType);
        changed |= copyIfPresent(user.getLocation(), record.getLocation(), record::setLocation);
        changed |= copyIfPresent(user.getBranch(), record.getBranch(), record::setBranch);
        changed |= copyIfPresent(user.getDutySubOrganization(), record.getDutySubOrganization(),
                record::setDutySubOrganization);
        changed |= copyIfPresent(user.getPhoneNumber(), record.getPhoneNumber(), record::setPhoneNumber);
        changed |= copyIfPresent(user.getEmail(), record.getEmail(), record::setEmail);

        return changed;
    }

    private static boolean copyIfPresent(String incoming, String current, Consumer<String> setter) {
        if (StringUtils.isBlank(incoming) || Objects.equals(incoming, current)) {
            return false;
        }
        setter.accept(incoming);
        return true;
    }
}
