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

/**
 * The Caffeine caches in front of the directory.
 *
 * <p>There is no {@code Caffeine} bean here for Spring's own cache manager. One used to
 * exist to back {@code @Cacheable} on the group builder, which gave group trees two
 * caches with different refresh settings, the lower one answering the upper one's
 * refreshes. Group caching now lives entirely in these loading caches.
 */
@Configuration
public class CacheConfig {

    @Bean("ldapGroupLoadingCache")
    public LoadingCache<String, LdapGroup> ldapGroupLoadingCache(ApplicationProperties properties,
                                                                 LdapGroupLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

    @Bean("ldapUserLoadingCache")
    public LoadingCache<String, LdapUser> ldapUserLoadingCache(ApplicationProperties properties,
                                                               LdapUserLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

    @Bean("ldapCountLoadingCache")
    public LoadingCache<String, Long> ldapCountLoadingCache(ApplicationProperties properties,
                                                                 LdapTotalCountLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

    @Bean("ldapOrgLoadingCache")
    public LoadingCache<String, OrgNode> ldapOrgLoadingCache(ApplicationProperties properties,
                                                               LdapOrgLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationRefreshMinutes()))
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationExpirationMinutes()))  // default expiration
                .build(loader);
    }

}
