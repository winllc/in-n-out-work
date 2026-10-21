package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The public API, webhook and report settings bind as operators write them and refuse what cannot work. */
class IntegrationSettingsValidationTest {

    @EnableConfigurationProperties(ApplicationProperties.class)
    static class Bound {
    }

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(Bound.class)
            .withPropertyValues("application.user-base-dn=dc=example,dc=com");

    @Test
    void theDefaultsLeaveWebhooksOffAndKeepOneTimeZone() {
        RUNNER.run(context -> {
            ApplicationProperties p = context.getBean(ApplicationProperties.class);
            assertThat(p.getWebhooks().isConfigured()).isFalse();
            assertThat(p.getWebhooks().getAllowedCidrs()).isEmpty();
            assertThat(p.getApi().getMaxTokenDays()).isEqualTo(366);
            assertThat(p.getReports().siteZones()).isEmpty();
        });
    }

    @Test
    void valuesBindFromTheEnvironmentsCommaLists() {
        RUNNER.withPropertyValues("application.webhooks.secret-key=" + "k".repeat(40),
                        "application.webhooks.allowed-cidrs=10.20.0.0/16,fd00:1::/64",
                        "application.reports.site-time-zones=New York=America/New_York,Los Angeles=America/Los_Angeles")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ApplicationProperties p = context.getBean(ApplicationProperties.class);
                    assertThat(p.getWebhooks().isConfigured()).isTrue();
                    assertThat(p.getWebhooks().getAllowedCidrs()).containsExactly("10.20.0.0/16", "fd00:1::/64");
                    assertThat(p.getReports().siteZones()).containsEntry("los angeles", ZoneId.of("America/Los_Angeles"));
                });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "application.webhooks.secret-key=tooshort                | APPLICATION_WEBHOOKS_SECRETKEY",
            "application.webhooks.allowed-cidrs=intranet.example.com | APPLICATION_WEBHOOKS_ALLOWEDCIDRS",
            "application.webhooks.max-attempts=0                     | APPLICATION_WEBHOOKS_MAXATTEMPTS",
            "application.api.max-token-days=0                        | APPLICATION_API_MAXTOKENDAYS",
            "application.api.max-page-size=100000                    | APPLICATION_API_MAXPAGESIZE",
            "application.reports.site-time-zones=Paris               | APPLICATION_REPORTS_SITETIMEZONES",
            "application.reports.site-time-zones=Paris=Mars/Olympus  | APPLICATION_REPORTS_SITETIMEZONES",
    })
    void impossibleValuesStopStartupNamingTheSetting(String setting, String named) {
        RUNNER.withPropertyValues(setting.strip())
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(named.strip()));
    }
}
