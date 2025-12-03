package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.PermissionRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.PermissionRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.UserRestService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PermissionService {

    @Autowired
    private UserRecordRepository userRecordRepository;
    @Autowired
    private PermissionRecordRepository permissionRecordRepository;

    public List<LdapDn> getUserGroupPermissions(LdapDn userDn){
        List<PermissionRecord> byUserDn = permissionRecordRepository.findByUser_Dn(userDn.dn());

        return byUserDn.stream()
                .map(r -> new LdapDn(r.getGroupDn()))
                .toList();
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
