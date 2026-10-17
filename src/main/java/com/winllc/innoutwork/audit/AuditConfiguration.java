package com.winllc.innoutwork.audit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code ens.audit.*}; the application's own property scan covers only the config package. */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfiguration {
}
