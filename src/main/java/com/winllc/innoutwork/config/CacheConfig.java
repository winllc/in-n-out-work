package com.winllc.innoutwork.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.service.loader.LdapGroupLoader;
import com.winllc.innoutwork.service.loader.LdapTotalCountLoader;
import com.winllc.innoutwork.service.loader.LdapUserLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

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

}
