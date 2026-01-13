package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "application.inactive-cron")
@Component
@Data
public class InactiveCronProperties {
    private long fixedRate = 1800000;
    private long initialDelay = 1800000;
}
