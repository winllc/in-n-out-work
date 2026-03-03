package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class PermissionEvaluator {

    private final PermissionService permissionService;
    @Autowired
    private GroupRecordRepository groupRecordRepository;
    @Autowired
    private LdapService ldapService;
    @Autowired
    private GroupService groupService;

    public PermissionEvaluator(PermissionService permissionService) {
        this.permissionService = permissionService;
    }


    public boolean groupCheck(String group, Authentication authentication) {
        LdapDn ldapDn = new LdapDn(group);

        List<LdapDn> userGroups = permissionService.getUserGroupPermissionsAndMemberOfGroups(new LdapDn(authentication.getName()));

        return userGroups.stream()
                .anyMatch(g -> g.equals(ldapDn));
    }

    public boolean userManagerCheck(String userDn, Authentication authentication) {
        LdapDn userLdapDn = new LdapDn(userDn);
        LdapDn managerLdapDn = new LdapDn(authentication.getName());

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(userLdapDn.dn());

        boolean isUserManager = false;

        for(LdapGroup group : groupsForUser){

            isUserManager = groupService.getManagersForGroup(group.getDn()).stream()
                    .anyMatch(m -> m.equalsIgnoreCase(managerLdapDn.dn()));

            if(isUserManager){break;}
        }

        return isUserManager;
    }

}
