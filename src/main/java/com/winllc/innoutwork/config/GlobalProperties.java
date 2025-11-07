package com.winllc.innoutwork.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GlobalProperties {

    @Value("${application.banner-text}")
    private String bannerText;

    public static String BANNER_TEXT;

    @PostConstruct
    public void init() {
        BANNER_TEXT = bannerText;
    }
}