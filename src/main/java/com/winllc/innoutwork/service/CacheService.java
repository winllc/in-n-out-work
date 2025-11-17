package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CacheService {
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
        return cache.get(dn); // triggers load or async refresh
    }

    public Long getLdapCount(String dn) {
        return ldapCountLoadingCache.get(dn);
    }
}
