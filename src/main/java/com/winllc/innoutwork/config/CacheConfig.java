package com.winllc.innoutwork.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.service.loader.LdapGroupLoader;
import com.winllc.innoutwork.service.loader.LdapOrgLoader;
import com.winllc.innoutwork.service.loader.LdapTotalCountLoader;
import com.winllc.innoutwork.service.loader.LdapUserLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Configuration
public class CacheConfig {

    /**
     * Where refreshAfterWrite reloads run. Caffeine's default is ForkJoinPool.commonPool(), whose threads'
     * context class loader cannot see the application's jars when it runs as a Boot jar. JNDI loads Spring
     * LDAP's DirObjectFactory through that loader, skips it silently when it can't, and search results then
     * reach the context mappers as raw LdapCtx instead of DirContextAdapter (a ClassCastException in LdapService).
     */
    static final Executor REFRESH_EXECUTOR = task -> {
        ClassLoader appClassLoader = CacheConfig.class.getClassLoader();
        ForkJoinPool.commonPool().execute(() -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(appClassLoader);
            try {
                task.run();
            } finally {
                thread.setContextClassLoader(previous);
            }
        });
    };

    @Bean
    public Caffeine<Object, Object> caffeineConfig(ApplicationProperties properties) {
        return Caffeine.newBuilder()
                //.refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .maximumSize(5000);
    }

    @Bean("ldapGroupLoadingCache")
    public LoadingCache<String, LdapGroup> ldapGroupLoadingCache(ApplicationProperties properties,
                                                                 LdapGroupLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .executor(REFRESH_EXECUTOR)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

    @Bean("ldapUserLoadingCache")
    public LoadingCache<String, LdapUser> ldapUserLoadingCache(ApplicationProperties properties,
                                                               LdapUserLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .executor(REFRESH_EXECUTOR)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

    @Bean("ldapCountLoadingCache")
    public LoadingCache<String, Long> ldapCountLoadingCache(ApplicationProperties properties,
                                                                 LdapTotalCountLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .executor(REFRESH_EXECUTOR)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

    @Bean("ldapOrgLoadingCache")
    public LoadingCache<String, OrgNode> ldapOrgLoadingCache(ApplicationProperties properties,
                                                               LdapOrgLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .executor(REFRESH_EXECUTOR)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

}
