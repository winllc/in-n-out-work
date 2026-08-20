package com.winllc.innoutwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Schedule for the directory metadata refresh. Directory metadata moves rarely, so the
 * default is hourly, offset from startup so a restart does not immediately sweep LDAP.
 */
@ConfigurationProperties(prefix = "application.refresh-user-records-cron")
@Component
@Data
public class RefreshUserRecordsCronProperties {
    private long fixedRate = 3600000;
    private long initialDelay = 300000;
    private boolean enabled = true;
}
