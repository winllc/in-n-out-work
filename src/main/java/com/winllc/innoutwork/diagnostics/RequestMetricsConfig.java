package com.winllc.innoutwork.diagnostics;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.ldap.core.LdapTemplate;

/**
 * Request metrics: with {@code application.diagnostics.request-metrics: true} every request logs its SQL
 * statement and LDAP operation counts. Off by default; meant for measuring a page, not for leaving on.
 */
@Configuration
@ConditionalOnProperty(name = "application.diagnostics.request-metrics", havingValue = "true")
public class RequestMetricsConfig {

    /** Hibernate hands every statement it prepares to the inspector; count it and pass it on unchanged. */
    @Bean
    HibernatePropertiesCustomizer requestMetricsStatementCounter() {
        StatementInspector counter = sql -> {
            RequestMetrics.sqlStatement();
            return sql;
        };
        return properties -> properties.put("hibernate.session_factory.statement_inspector", counter);
    }

    /**
     * Counts the template's operations. Only the template's context source is wrapped: login binds, which
     * Spring Security makes through the context source directly, are not counted.
     */
    @Bean
    static BeanPostProcessor requestMetricsLdapCounter() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof LdapTemplate template && !(template.getContextSource() instanceof CountingContextSource)) {
                    template.setContextSource(new CountingContextSource(template.getContextSource()));
                }
                return bean;
            }
        };
    }

    @Bean
    FilterRegistrationBean<RequestMetricsFilter> requestMetricsFilter() {
        FilterRegistrationBean<RequestMetricsFilter> registration = new FilterRegistrationBean<>(new RequestMetricsFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE); // outside security, so authentication's lookups count too
        return registration;
    }
}
