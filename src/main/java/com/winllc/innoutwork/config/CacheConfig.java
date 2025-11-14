package com.winllc.innoutwork.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.LdapGroupLoader;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))  // default expiration
                .maximumSize(5000);
    }

    @Bean("ldapGroupLoadingCache")
    public LoadingCache<String, LdapGroup> ldapGroupLoadingCache(ApplicationProperties properties,
                                                                 LdapGroupLoader loader) {
        return Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationMinutes()))  // adjust as needed
                .refreshAfterWrite(Duration.ofMinutes(properties.getCacheDurationMinutes() / 2))
                .build(loader);
    }

}
