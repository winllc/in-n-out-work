package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.CacheLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class LdapTotalCountLoader implements CacheLoader<String, Long> {

    private final LdapService ldapService;

    public LdapTotalCountLoader(LdapService ldapService) {
        this.ldapService = ldapService;
    }

    @Override
    public Long load(String dn) {
        return ldapService.count(dn, "objectClass=*");
    }

    @Override
    public Long reload(String dn, Long oldValue) throws Exception {
        // reload is async and non-blocking for callers
        return ldapService.count(dn, "objectClass=*");
    }
}
