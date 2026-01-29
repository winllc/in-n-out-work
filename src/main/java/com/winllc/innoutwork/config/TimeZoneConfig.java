package com.winllc.innoutwork.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

@Configuration
public class TimeZoneConfig {

    private final ApplicationProperties properties;

    public TimeZoneConfig(ApplicationProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone(properties.getTimeZone()));
    }
}
