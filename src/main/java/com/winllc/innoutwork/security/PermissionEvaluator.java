package com.winllc.innoutwork.security;

import com.fasterxml.jackson.core.JsonProcessingException;
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

    public boolean checkPermission(String group, Authentication authentication) {

        List<String> groupMembers = ldapService.getGroupMembers(group);

        return groupMembers.stream()
                .map(m -> m.replace(", ", ","))
                .anyMatch(m -> m.equalsIgnoreCase(authentication.getName()));
    }
}
