package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(PermissionEvaluator.class);

    private final PermissionService permissionService;
    private final LdapService ldapService;
    private final GroupService groupService;

    public PermissionEvaluator(PermissionService permissionService, LdapService ldapService, GroupService groupService) {
        this.permissionService = permissionService;
        this.ldapService = ldapService;
        this.groupService = groupService;
    }


    public boolean groupCheck(String group, Authentication authentication) {
        LdapDn ldapDn = new LdapDn(group);

        List<LdapDn> userGroups = permissionService.getUserGroupPermissionsAndMemberOfGroups(new LdapDn(authentication.getName()));

        boolean allowed = userGroups.stream()
                .anyMatch(g -> g.equals(ldapDn));

        // Denials surface to the user as a bare 403, so record the decision and the
        // groups it was made against.
        if (allowed) {
            log.debug("Group access to {} granted for {}", group, authentication.getName());
        } else {
            log.debug("Group access to {} denied for {}; holds {} group(s)",
                    group, authentication.getName(), userGroups.size());
        }

        return allowed;
    }

    public boolean userManagerCheck(String userDn, Authentication authentication) {
        LdapDn userLdapDn = new LdapDn(userDn);
        LdapDn managerLdapDn = new LdapDn(authentication.getName());

        List<LdapGroup> groupsForUser = ldapService.findGroupsForUser(userLdapDn.dn());

        boolean isUserManager = false;

        for(LdapGroup group : groupsForUser){

            isUserManager = groupService.getManagersForGroup(group.getDn()).stream()
                    .anyMatch(m -> m.equalsIgnoreCase(managerLdapDn.dn()));

            if(isUserManager){
                log.debug("{} manages {} via group {}", managerLdapDn.dn(), userLdapDn.dn(), group.getDn());
                break;
            }
        }

        if (!isUserManager) {
            log.debug("{} does not manage {} in any of its {} group(s)",
                    managerLdapDn.dn(), userLdapDn.dn(), groupsForUser.size());
        }

        return isUserManager;
    }

}
