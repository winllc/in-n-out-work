package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.*;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.util.Chunks;
import io.micrometer.common.util.StringUtils;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    /** Creates user records without duplicating one when two requests race; see {@link UserRecordStore}. */
    private final UserRecordStore userRecords;

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
        this.userRecords = new UserRecordStore(userRecordRepository);
    }

    public Optional<UserRecord> getUserByDn(LdapDn dn) {
        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(dn.dn());
        if(recordOptional.isPresent()){
            return recordOptional;
        }else{
            Optional<LdapUser> userOptional = ldapService.lookupUser(dn);

            if(userOptional.isPresent()){
                LdapUser ldapUser = userOptional.get();
                return Optional.of(userRecords.insertOrFind(dn.dn(), () -> new UserRecord(ldapUser)).record());
            }

        }
        return Optional.empty();
    }

    public UserRecord updateProfile(Authentication authentication, ProfileForm form) {
        log.debug("Update Notes {}",  authentication.getName());

        return userRecords.update(authentication.getName(), userRecord -> {
            userRecord.setNotes(form.getNotes());
            if(StringUtils.isNotBlank(form.getLoginTime())) {
                userRecord.setChosenLoginTime(LocalTime.parse(form.getLoginTime(), DateTimeFormatter.ISO_TIME));
            }
        });
    }

    public UserRecord updateRole(LdapDn dn, UserRoleEnum role) {
        return userRecords.update(dn.dn(), userRecord -> userRecord.setUserRole(role));
    }

    public UserRecord updateGroupFavorite(Authentication authentication, GroupFavorite groupFavorite) {
        log.debug("Update favorite groups {}: {}",  authentication.getName(), groupFavorite);

        return userRecords.update(authentication.getName(), userRecord -> {
            if(groupFavorite.isSelected()){
                userRecord.addGroup(groupFavorite.getGroupDn());
            }else{
                userRecord.removeGroup(groupFavorite.getGroupDn());
            }
        });
    }

    public UserStatus getUserStatus(String dn, HttpSession session){
        LocalDate day = CheckInOutService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS).toLocalDate();

        return buildStatus(dn, checkInOutService.findRecordsForUser(dn, session),
                userRecordRepository.findByDnIgnoreCase(dn),
                userEventRecordRepository.findByDnIgnoreCaseAndDate(dn, day));
    }

    /**
     * {@link #getUserStatus} for many users, in the order given, with three queries per
     * {@link Chunks#IN_LIST_SIZE} users rather than three per user.
     */
    public List<UserStatus> getUserStatuses(Collection<String> dns, HttpSession session) {
        List<String> given = dns.stream().filter(Objects::nonNull).toList();
        if (given.isEmpty()) {
            return List.of();
        }
        LocalDate day = CheckInOutService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS).toLocalDate();
        Set<String> lower = new LinkedHashSet<>();
        given.forEach(dn -> lower.add(dn.toLowerCase()));

        Map<String, List<CheckInOutRecord>> records = checkInOutService.findRecordsForUsers(lower, session);
        Map<String, UserRecord> userRecords = new HashMap<>();
        Map<String, List<UserEventRecord>> events = new HashMap<>();
        for (List<String> chunk : Chunks.of(lower)) {
            userRecordRepository.findAllByLowercaseDnIn(chunk)
                    .forEach(r -> userRecords.putIfAbsent(r.getDn().toLowerCase(), r));
            userEventRecordRepository.findByLowercaseDnInAndDateBetween(chunk, day, day)
                    .forEach(e -> events.computeIfAbsent(e.getDn().toLowerCase(), k -> new ArrayList<>()).add(e));
        }

        return given.stream()
                .map(dn -> buildStatus(dn, records.getOrDefault(dn.toLowerCase(), List.of()),
                        Optional.ofNullable(userRecords.get(dn.toLowerCase())),
                        events.getOrDefault(dn.toLowerCase(), List.of())))
                .toList();
    }

    /** A user's status for a day from that day's records, their user record and their status entries. */
    private static UserStatus buildStatus(String dn, List<CheckInOutRecord> todaysRecordsForUser,
                                          Optional<UserRecord> recordOptional, List<UserEventRecord> todaysEvents) {
        UserStatus status = UserStatus.builder()
                .dn(dn).build();

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

        if(recordOptional.isPresent()){
            UserRecord record = recordOptional.get();
            status.setNotes(record.getNotes());
            status.setOrganization(record.getOrganization());
            status.setEmployeeType(record.getEmployeeType());
        }

        todaysEvents.stream()
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
        List<LdapUser> reports = findDirectReports(managerDn);
        List<UserStatus> statuses = getUserStatuses(reports.stream().map(LdapUser::getDn).toList(), session);

        List<UserStatus> described = new ArrayList<>();
        for (int i = 0; i < reports.size(); i++) {
            described.add(describeReport(reports.get(i), statuses.get(i)));
        }
        described.sort(Comparator.comparing(UserStatus::getCn, String.CASE_INSENSITIVE_ORDER));
        return described;
    }

    /**
     * The directory entries of the users who report directly to {@code managerDn}, without looking
     * up anyone's attendance. Empty when the manager is not in the directory or carries no manager id.
     */
    public List<LdapUser> findDirectReports(LdapDn managerDn) {
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
    private UserStatus describeReport(LdapUser ldapUser, UserStatus status) {

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
        UserRecordStore.Result created = null;
        if(byDnIgnoreCase.isEmpty()){
            created = userRecords.insertOrFind(dn.toString(), () -> UserRecord.builder()
                    .dn(dn.toString())
                    .employeeType(ldapUser.getEmployeeType())
                    .organization(ldapUser.getOrganization())
                    .location(ldapUser.getLocation())
                    .branch(ldapUser.getBranch())
                    .dutySubOrganization(ldapUser.getDutySubOrganization())
                    .userRole(UserRoleEnum.USER)
                    .build());
        }
        if(created != null && created.inserted()){
            return created.record();
        }else{
            // Existing already, or created by a concurrent request a moment ago: bring it up to date.
            UserRecord userRecord = created != null ? created.record() : byDnIgnoreCase.get();
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
        Map<UserRecord, LdapUser> fresh = new IdentityHashMap<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (LdapUser user : batch) {
            UserRecord record = existing.get(user.getDn().toLowerCase());

            if (record == null) {
                UserRecord freshRecord = UserRecord.builder()
                        .dn(user.getDn())
                        .userRole(UserRoleEnum.USER)
                        .build();
                applyDirectoryMetadata(user, freshRecord);
                toSave.add(freshRecord);
                fresh.put(freshRecord, user);
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
            try {
                userRecordRepository.saveAll(toSave);
            } catch (DataIntegrityViolationException e) {
                // Someone signed in and got a record between this batch's lookup and its insert, and the
                // one-record-per-DN index rejected the whole batch. Retry it a record at a time.
                log.debug("Directory refresh: batch hit a record created concurrently; saving one by one");
                for (UserRecord record : toSave) {
                    LdapUser user = fresh.get(record);
                    if (user == null) {
                        userRecordRepository.save(record);
                        continue;
                    }
                    record.setId(null); // assigned by the rolled-back insert
                    UserRecordStore.Result result = userRecords.insertOrFind(record.getDn(), () -> record);
                    if (!result.inserted() && applyDirectoryMetadata(user, result.record())) {
                        userRecordRepository.save(result.record());
                    }
                }
            }
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
