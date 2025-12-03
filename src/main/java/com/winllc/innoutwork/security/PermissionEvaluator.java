package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PermissionEvaluator {

    private final LdapService ldapService;

    public PermissionEvaluator(LdapService ldapService) {
        this.ldapService = ldapService;
    }

    public boolean groupCheck(String group, Authentication authentication) {

        List<String> groupMembers = ldapService.getGroupMembers(LdapDn.builder().dn(group).build());

        return groupMembers.stream()
                .map(m -> LdapDn.builder().dn(m).build())
                .anyMatch(m -> m.toString().equalsIgnoreCase(authentication.getName()));
    }
}
