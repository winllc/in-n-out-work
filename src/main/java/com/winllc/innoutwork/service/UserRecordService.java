package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.GroupFavorite;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.stream.Stream;

@Service
public class UserRecordService {

    private static final Logger log = LoggerFactory.getLogger(UserRecordService.class);

    private final UserRecordRepository userRecordRepository;

    public UserRecordService(UserRecordRepository userRecordRepository) {
        this.userRecordRepository = userRecordRepository;
    }

    public Optional<UserRecord> getUserByDn(LdapDn dn) {
        return userRecordRepository.findByDnIgnoreCase(dn.dn());
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
        if(form.getStatus() != null &&
                Stream.of(UserStatusEnum.values()).anyMatch(userStatus -> userStatus.name().equals(form.getStatus()))) {
            userRecord.setStatus(UserStatusEnum.valueOf(form.getStatus()));
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
}
