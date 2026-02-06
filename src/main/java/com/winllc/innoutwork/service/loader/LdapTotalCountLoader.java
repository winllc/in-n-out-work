package com.winllc.innoutwork.service.loader;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.LdapService;
import org.springframework.stereotype.Component;

@Component
public class LdapTotalCountLoader implements CacheLoader<String, Long> {

    private final ApplicationProperties properties;
    private final LdapService ldapService;

    public LdapTotalCountLoader(LdapService ldapService, ApplicationProperties properties) {
        this.ldapService = ldapService;
        this.properties = properties;
    }

    @Override
    public Long load(String dn) {
        return ldapService.count(dn, properties.getUserLdapFilter());
    }

    @Override
    public Long reload(String dn, Long oldValue) throws Exception {
        // reload is async and non-blocking for callers
        return ldapService.count(dn, properties.getUserLdapFilter());
    }
}
