package com.winllc.innoutwork.config;

import com.winllc.innoutwork.web.SecurityHeadersFilter;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Registers {@link SecurityHeadersFilter} for every request and dispatch, outside Spring Security. */
@Configuration
public class SecurityHeadersConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityHeadersConfig.class);

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter(EnsProperties properties) {
        boolean reportOnly = properties.getCsp().isReportOnly();
        if (reportOnly) {
            log.warn("ENS_CSP_REPORT_ONLY is true: the Content-Security-Policy is reported on but not enforced");
        }
        FilterRegistrationBean<SecurityHeadersFilter> registration =
                new FilterRegistrationBean<>(new SecurityHeadersFilter(reportOnly));
        // After ForwardedHeaderFilter (HIGHEST_PRECEDENCE), so isSecure() reflects X-Forwarded-Proto when the
        // forward-headers strategy is framework.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.FORWARD);
        return registration;
    }
}
