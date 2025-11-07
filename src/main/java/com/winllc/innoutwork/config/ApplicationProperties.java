package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "application")
public class ApplicationProperties {
    private String baseDn;
    private String groupsBaseDn;
    private int cacheDurationMinutes = 60;
}
