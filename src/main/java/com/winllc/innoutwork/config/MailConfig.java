package com.winllc.innoutwork.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Startup checks of settings with no safe packaged default: the mail server and its sender, and the
 * administrators. A mail server without a From address stops startup; no mail server, or no administrator, is
 * only warned about, since ReadyRoom works without either until someone needs them.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    private final Environment environment;
    private final ApplicationProperties properties;

    public MailConfig(Environment environment, ApplicationProperties properties) {
        this.environment = environment;
        this.properties = properties;
        check(environment.getProperty("spring.mail.host"), properties.getNotificationSenderEmail());
    }

    /** A mail server needs a From address; without one every alert would be refused by the server. */
    static void check(String mailHost, String sender) {
        if (StringUtils.hasText(mailHost) && !StringUtils.hasText(sender)) {
            throw new IllegalStateException("SPRING_MAIL_HOST is set but APPLICATION_NOTIFICATIONSENDEREMAIL is not:"
                    + " set the From address of absence alerts (e.g. readyroom@example.com)");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutMissingSettings() {
        if (!StringUtils.hasText(environment.getProperty("spring.mail.host"))) {
            log.warn("No mail server is configured (SPRING_MAIL_HOST): absence alerts are recorded but not e-mailed");
        }
        if (properties.getSuperUserDns() == null || properties.getSuperUserDns().isEmpty()) {
            log.warn("No administrator is configured (APPLICATION_SUPERUSERDNS_0): unless a user already holds the"
                    + " ADMIN role, nobody can administer ReadyRoom or reach the license screen");
        }
    }
}
