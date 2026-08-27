package com.winllc.innoutwork.service.loader;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LdapTotalCountLoader implements CacheLoader<String, Long> {

    private static final Logger log = LoggerFactory.getLogger(LdapTotalCountLoader.class);

    private final ApplicationProperties properties;
    private final LdapService ldapService;

    public LdapTotalCountLoader(LdapService ldapService, ApplicationProperties properties) {
        this.ldapService = ldapService;
        this.properties = properties;
    }

    @Override
    public Long load(String dn) {
        long start = System.currentTimeMillis();
        Long count = ldapService.count(dn, properties.getUserLdapFilter());

        log.debug("Counted {} entries under {} (filter {}) in {}ms",
                count, dn, properties.getUserLdapFilter(), System.currentTimeMillis() - start);

        return count;
    }

    @Override
    public Long reload(String dn, Long oldValue) throws Exception {
        // reload is async and non-blocking for callers
        Long count = ldapService.count(dn, properties.getUserLdapFilter());

        // A membership count that moves sharply is usually the first sign of a directory
        // or filter problem, so make the before/after visible.
        if (log.isDebugEnabled() && !java.util.Objects.equals(oldValue, count)) {
            log.debug("Entry count under {} changed from {} to {}", dn, oldValue, count);
        }

        return count;
    }
}
