package com.winllc.innoutwork.service;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.PermissionRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.PermissionRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final UserRecordRepository userRecordRepository;
    private final PermissionRecordRepository permissionRecordRepository;
    private final LdapService ldapService;
    private final TrackedPeopleLimit peopleLimit;
    private AuditLog audit = AuditLog.NOOP;

    public PermissionService(UserRecordRepository userRecordRepository,
                             PermissionRecordRepository permissionRecordRepository, LdapService ldapService,
                             TrackedPeopleLimit peopleLimit) {
        this.userRecordRepository = userRecordRepository;
        this.permissionRecordRepository = permissionRecordRepository;
        this.ldapService = ldapService;
        this.peopleLimit = peopleLimit;
    }

    /** Grants and revocations are recorded with the change, in one transaction: never one without the other. */
    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    public List<LdapDn> getUserGroupPermissions(LdapDn userDn){
        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(userDn.dn());

        // This list drives what the user is allowed to see, so an empty result is the
        // usual explanation for an unexpectedly bare Groups page.
        log.debug("Resolved {} group permission(s) for {}", groupsForUser.size(), userDn.dn());

        return groupsForUser.stream()
                .map(r -> new LdapDn(r.getDn()))
                .toList();
    }

    /**
     * The groups a user may see: those they are a member of. Permissions are currently the same directory
     * lookup, so this reads it once instead of twice and removes duplicates.
     */
    public List<LdapDn> getUserGroupPermissionsAndMemberOfGroups(LdapDn userDn){
        return new ArrayList<>(new LinkedHashSet<>(getUserGroupPermissions(userDn)));
    }

    public void addGroupToUser(LdapDn groupDn, LdapDn userDn) {

        UserRecord userRecord = getOrCreateUserRecord(userDn);

        // Saved directly rather than added to the user's lazy permissions list and cascaded: that needs the
        // list loaded inside a transaction, and saving the whole user could overwrite a concurrent change.
        audit.inTransaction(() -> {
            permissionRecordRepository.save(PermissionRecord.builder()
                    .user(userRecord)
                    .groupDn(groupDn.dn())
                    .build());
            audit.record(permissionEvent("account.permission-granted", groupDn, userDn));
        });

        // Access changes are audit-worthy, so they stay at info.
        log.info("Granted group permission {} to {}", groupDn.dn(), userDn.dn());
    }

    public void removeGroupFromUser(LdapDn groupDn, LdapDn userDn) {
        Optional<PermissionRecord> recordOptional = permissionRecordRepository.findFirstByGroupDnIgnoreCaseAndUser_DnIgnoreCase(groupDn.dn(), userDn.dn());
        if(recordOptional.isPresent()){
            audit.inTransaction(() -> {
                permissionRecordRepository.delete(recordOptional.get());
                audit.record(permissionEvent("account.permission-revoked", groupDn, userDn));
            });

            log.info("Revoked group permission {} from {}", groupDn.dn(), userDn.dn());
        } else {
            // The caller asked to revoke something that was never granted; harmless, but
            // it means the UI and the database disagree.
            log.debug("No group permission {} held by {}; nothing to revoke", groupDn.dn(), userDn.dn());
        }

    }

    private static AuditEntry permissionEvent(String action, LdapDn groupDn, LdapDn userDn) {
        return AuditEntry.of(AuditEntry.Category.ACCOUNT, action)
                .target("user", userDn.dn(), userDn.getCn())
                .detail("group", groupDn.dn());
    }

    private UserRecord getOrCreateUserRecord(LdapDn userDn){
        return new UserRecordStore(userRecordRepository, peopleLimit).findOrCreate(userDn.dn(), () -> {
            // Incidental to the grant, which is logged at info by the caller.
            log.debug("Creating user record for {} on first permission change", userDn.dn());
            return UserRecord.builder().dn(userDn.dn()).build();
        });
    }
}
