package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PermissionEvaluator {


    private final PermissionService permissionService;

    public PermissionEvaluator(PermissionService permissionService) {
        this.permissionService = permissionService;
    }


    public boolean groupCheck(String group, Authentication authentication) {
        LdapDn ldapDn = new LdapDn(group);

        List<LdapDn> userGroups = permissionService.getUserGroupPermissionsAndMemberOfGroups(new LdapDn(authentication.getName()));

        return userGroups.stream()
                .anyMatch(g -> g.equals(ldapDn));
    }

}
