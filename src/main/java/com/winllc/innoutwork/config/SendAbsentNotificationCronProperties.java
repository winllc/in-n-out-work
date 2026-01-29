package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "application.send-absent-notification-cron")
@Component
@Data
public class SendAbsentNotificationCronProperties {
    private long fixedRate = 1800000;
    private long initialDelay = 1800000;
}
