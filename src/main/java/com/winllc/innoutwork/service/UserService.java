package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.*;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.UserRestService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRecordRepository userRecordRepository;

    @Autowired
    private LdapService ldapService;
    @Autowired
    private UserRestService userRestService;

    public UserService(UserRecordRepository userRecordRepository) {
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
        }

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(ldapDn);
        builder.memberOf(groupsForUser);

        UserStatus userStatus = userRestService.getUserStatus(ldapDn, session);
        builder.status(userStatus.getStatus());
        builder.organization(userStatus.getOrganization());
        builder.employeeType(userStatus.getEmployeeType());
        builder.location(userStatus.getLocation());

        return builder.build();
    }
}
