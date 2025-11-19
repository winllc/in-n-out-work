package com.winllc.innoutwork.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GlobalProperties {

    @Value("${application.banner-text}")
    private String bannerText;
    @Value("${application.banner-color:darkgreen}")
    private String bannerColor;
    @Value("${application.banner-text-color:white}")
    private String bannerTextColor;

    public static String BANNER_TEXT;
    public static String BANNER_COLOR;
    public static String BANNER_TEXT_COLOR;

    @PostConstruct
    public void init() {
        BANNER_TEXT = bannerText;
        BANNER_COLOR = bannerColor;
        BANNER_TEXT_COLOR = bannerTextColor;
    }
}