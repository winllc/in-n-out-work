package com.winllc.innoutwork.service.loader;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LdapUserLoader implements CacheLoader<String, LdapUser> {

    private static final Logger log = LoggerFactory.getLogger(LdapUserLoader.class);

    private final LdapService ldapGroupService;

    public LdapUserLoader(LdapService ldapGroupService) {
        this.ldapGroupService = ldapGroupService;
    }

    @Override
    public LdapUser load(String dn) {
        return ldapGroupService.lookupUser(LdapDn.builder().dn(dn).build()).orElse(null);
    }

    @Override
    public LdapUser reload(String dn, LdapUser oldValue) throws Exception {
        // reload is async and non-blocking for callers
        try {
            return ldapGroupService.lookupUser(LdapDn.builder().dn(dn).build()).orElse(null);
        }catch (Exception e) {
            log.error("Failed to reload ldap group %s".formatted(dn), e);
            return oldValue;
        }
    }
}