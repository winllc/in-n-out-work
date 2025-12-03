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

    private final LdapService ldapService;

    @Autowired
    private PermissionService permissionService;

    public PermissionEvaluator(LdapService ldapService) {
        this.ldapService = ldapService;
    }

    public boolean groupCheck(String group, Authentication authentication) {

        LdapDn ldapDn = new LdapDn(group);

        List<LdapDn> userGroups = permissionService.getUserGroupPermissions(new LdapDn(authentication.getName()));

        List<String> groupMembers = ldapService.getGroupMembers(LdapDn.builder().dn(group).build());

        boolean inGroup = groupMembers.stream()
                .map(m -> LdapDn.builder().dn(m).build())
                .anyMatch(m -> m.toString().equalsIgnoreCase(authentication.getName()));

        boolean allowedGroup = userGroups.stream()
                .anyMatch(g -> g.equals(ldapDn));

        return allowedGroup || inGroup;
    }
}
