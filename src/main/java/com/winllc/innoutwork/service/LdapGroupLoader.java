package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.winllc.innoutwork.data.LdapGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LdapGroupLoader implements CacheLoader<String, LdapGroup> {

    private final LdapService ldapGroupService;

    public LdapGroupLoader(LdapService ldapGroupService) {
        this.ldapGroupService = ldapGroupService;
    }

    @Override
    public LdapGroup load(String dn) {
        return ldapGroupService.buildGroupRecursiveInternal(dn);
    }

    @Override
    public LdapGroup reload(String dn, LdapGroup oldValue) throws Exception {
        // reload is async and non-blocking for callers
        return ldapGroupService.buildGroupRecursiveInternal(dn);
    }
}