package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class CacheService {
    private final LoadingCache<String, LdapGroup> cache;

    public CacheService(@Qualifier("ldapGroupLoadingCache")
                                 LoadingCache<String, LdapGroup> cache) {
        this.cache = cache;
    }

    public LdapGroup getGroup(String dn) {
        return cache.get(dn); // triggers load or async refresh
    }
}
