package com.winllc.innoutwork.service.loader;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LdapGroupLoader implements CacheLoader<String, LdapGroup> {

    private static final Logger log = LoggerFactory.getLogger(LdapGroupLoader.class);

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
        try {
            return ldapGroupService.buildGroupRecursiveInternal(dn);
        }catch (Exception e) {
            log.error("Failed to reload ldap group %s".formatted(dn), e);
            return oldValue;
        }
    }
}