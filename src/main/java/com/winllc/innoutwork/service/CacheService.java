package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final LoadingCache<String, LdapGroup> cache;
    private final LoadingCache<String, Long> ldapCountLoadingCache;

    public CacheService(@Qualifier("ldapGroupLoadingCache")
                                 LoadingCache<String, LdapGroup> cache,
                        @Qualifier("ldapCountLoadingCache")
                        LoadingCache<String, Long> ldapCountLoadingCache) {
        this.cache = cache;
        this.ldapCountLoadingCache = ldapCountLoadingCache;
    }

    public LdapGroup getGroup(String dn) {
        LdapGroup group = cache.get(dn); // triggers load or async refresh

        if (group == null) {
            // The loader returns null for a DN the directory cannot resolve; callers
            // dereference this, so it is the more interesting of the two outcomes.
            log.warn("No group found in the directory for {}", dn);
        } else {
            log.debug("Serving group {} ({} child groups)", dn, group.getChildren().size());
        }

        return group;
    }

    public Long getLdapCount(String dn) {
        Long count = ldapCountLoadingCache.get(dn);

        log.debug("Serving entry count {} for {}", count, dn);

        return count;
    }
}
