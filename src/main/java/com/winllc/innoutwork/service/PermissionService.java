package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.PermissionRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.PermissionRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.UserRestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PermissionService {

    private final UserRecordRepository userRecordRepository;
    private final PermissionRecordRepository permissionRecordRepository;
    private final LdapService ldapService;

    public PermissionService(UserRecordRepository userRecordRepository,
                             PermissionRecordRepository permissionRecordRepository, LdapService ldapService) {
        this.userRecordRepository = userRecordRepository;
        this.permissionRecordRepository = permissionRecordRepository;
        this.ldapService = ldapService;
    }

    public List<LdapDn> getUserGroupPermissions(LdapDn userDn){
        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(userDn.dn());

        List<PermissionRecord> byUserDn = permissionRecordRepository.findByUser_Dn(userDn.dn());

        return groupsForUser.stream()
                .map(r -> new LdapDn(r.getDn()))
                .toList();
    }

    public List<LdapDn> getUserGroupPermissionsAndMemberOfGroups(LdapDn userDn){
        List<LdapDn> permissionGroups = getUserGroupPermissions(userDn);

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(userDn.dn());
        List<LdapDn> memberOf = groupsForUser.stream()
                .map(g -> new LdapDn(g.getDn()))
                .toList();

        Set<LdapDn> memberOfGroups = new HashSet<>(permissionGroups);
        memberOfGroups.addAll(memberOf);

        return new ArrayList<>(memberOfGroups);
    }

    public void addGroupToUser(LdapDn groupDn, LdapDn userDn) {

        UserRecord userRecord = getOrCreateUserRecord(userDn);

        PermissionRecord permissionRecord = PermissionRecord.builder()
                .user(userRecord)
                .groupDn(groupDn.dn())
                .build();

        userRecord.getPermissions().add(permissionRecord);

        userRecordRepository.save(userRecord);
    }

    public void removeGroupFromUser(LdapDn groupDn, LdapDn userDn) {
        UserRecord userRecord = getOrCreateUserRecord(userDn);

        Optional<PermissionRecord> recordOptional = permissionRecordRepository.findFirstByGroupDnIgnoreCaseAndUser_DnIgnoreCase(groupDn.dn(), userDn.dn());
        if(recordOptional.isPresent()){
            PermissionRecord permissionRecord = recordOptional.get();
            userRecord.getPermissions().remove(permissionRecord);
            userRecordRepository.save(userRecord);

            permissionRecordRepository.delete(permissionRecord);
        }

    }

    private UserRecord getOrCreateUserRecord(LdapDn userDn){
        UserRecord userRecord;
        Optional<UserRecord> recordOptional = userRecordRepository.findByDnIgnoreCase(userDn.dn());
        if (recordOptional.isEmpty()) {
            userRecord = userRecordRepository.save(UserRecord.builder()
                    .dn(userDn.dn())
                    .build()
            );
        }else  {
            userRecord = recordOptional.get();
        }
        return userRecord;
    }
}
