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
        LdapUser user = ldapGroupService.lookupUser(LdapDn.builder().dn(dn).build()).orElse(null);

        if (user == null) {
            // Not fatal - the caller gets null - but a DN that never resolves usually means
            // a stale group membership or a base-DN misconfiguration.
            log.debug("Cache miss for user {} resolved to no directory entry", dn);
        } else {
            log.debug("Loaded user {} from the directory", dn);
        }

        return user;
    }

    @Override
    public LdapUser reload(String dn, LdapUser oldValue) throws Exception {
        // reload is async and non-blocking for callers
        try {
            log.debug("Refreshing cached user {}", dn);
            return ldapGroupService.lookupUser(LdapDn.builder().dn(dn).build()).orElse(null);
        }catch (Exception e) {
            log.error("Failed to reload ldap group %s".formatted(dn), e);
            log.warn("Serving stale cached user {} after refresh failure", dn);
            return oldValue;
        }
    }
}