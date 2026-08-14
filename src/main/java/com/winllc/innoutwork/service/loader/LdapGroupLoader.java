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
        // A cache miss means a full recursive walk of the group subtree, so it is worth
        // knowing when one happens and how long the directory took.
        long start = System.currentTimeMillis();
        LdapGroup group = ldapGroupService.buildGroupRecursiveInternal(dn);

        log.debug("Loaded group tree for {} in {}ms ({})", dn, System.currentTimeMillis() - start,
                group != null ? group.getChildren().size() + " child groups" : "not found");

        return group;
    }

    @Override
    public LdapGroup reload(String dn, LdapGroup oldValue) throws Exception {
        // reload is async and non-blocking for callers
        try {
            long start = System.currentTimeMillis();
            LdapGroup group = ldapGroupService.buildGroupRecursiveInternal(dn);

            log.debug("Refreshed group tree for {} in {}ms", dn, System.currentTimeMillis() - start);

            return group;
        }catch (Exception e) {
            log.error("Failed to reload ldap group %s".formatted(dn), e);
            // Callers keep serving the previous tree; surfaced at warn so a directory
            // outage is visible even when debug logging is off.
            log.warn("Serving stale cached group tree for {} after refresh failure", dn);
            return oldValue;
        }
    }
}