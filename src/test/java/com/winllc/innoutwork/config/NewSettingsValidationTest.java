package com.winllc.innoutwork.config;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.security.CoworkerVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings of status requests, privacy and retention bind from what operators write (lower case, comma lists,
 * the environment) and stop startup, naming the setting, on a value that cannot work.
 */
class NewSettingsValidationTest {

    @EnableConfigurationProperties(ApplicationProperties.class)
    static class Bound {
    }

    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(Bound.class)
            .withPropertyValues("application.user-base-dn=dc=example,dc=com");

    @Test
    void theDefaultsKeepTodaysBehaviour() {
        RUNNER.run(context -> {
            ApplicationProperties properties = context.getBean(ApplicationProperties.class);
            assertThat(properties.getPrivacy().getCoworkerVisibility()).isEqualTo(CoworkerVisibility.FULL);
            assertThat(properties.getRetention().getEventsDays()).isZero();
            assertThat(properties.getRetention().getCron()).isEqualTo("0 17 2 * * *");
            assertThat(properties.getEvents().getMaxRangeDays()).isEqualTo(366);
            assertThat(properties.getEvents().getRequestStatuses()).containsExactly(UserStatusEnum.OUT_OF_OFFICE,
                    UserStatusEnum.WORK_FROM_HOME, UserStatusEnum.TDY, UserStatusEnum.SCHEDULED_LEAVE,
                    UserStatusEnum.ABSENT_EXCUSED);
        });
    }

    @Test
    void valuesBindAsOperatorsWriteThem() {
        RUNNER.withPropertyValues("application.privacy.coworker-visibility=status",
                        "application.retention.events-days=400", "application.retention.cron=0 5 1 * * SUN",
                        "application.events.request-statuses=SCHEDULED_LEAVE,ABSENT_EXCUSED",
                        "application.events.max-range-days=31")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ApplicationProperties properties = context.getBean(ApplicationProperties.class);
                    assertThat(properties.getPrivacy().getCoworkerVisibility()).isEqualTo(CoworkerVisibility.STATUS);
                    assertThat(properties.getRetention().getEventsDays()).isEqualTo(400);
                    assertThat(properties.getEvents().getRequestStatuses())
                            .isEqualTo(List.of(UserStatusEnum.SCHEDULED_LEAVE, UserStatusEnum.ABSENT_EXCUSED));
                });
        RUNNER.withPropertyValues("application.privacy.coworker-visibility=NONE")
                .run(context -> assertThat(context.getBean(ApplicationProperties.class).getPrivacy()
                        .getCoworkerVisibility()).isEqualTo(CoworkerVisibility.NONE));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            // Bind failures: Spring Boot's failure analysis prints the setting and the values it takes.
            "application.privacy.coworker-visibility=partial | application.privacy.coworker-visibility",
            "application.events.request-statuses=ON_A_BEACH  | application.events.request-statuses",
            // Validation: the message names the setting and its environment variable.
            "application.retention.events-days=7             | APPLICATION_RETENTION_EVENTSDAYS",
            "application.retention.events-days=-1            | APPLICATION_RETENTION_EVENTSDAYS",
            "application.retention.cron=every night          | APPLICATION_RETENTION_CRON",
            "application.events.max-range-days=0             | APPLICATION_EVENTS_MAXRANGEDAYS",
            "application.events.max-range-days=5000          | APPLICATION_EVENTS_MAXRANGEDAYS",
            "application.events.request-statuses=STANDARD    | APPLICATION_EVENTS_REQUESTSTATUSES",
    })
    void impossibleValuesStopStartupNamingTheSetting(String setting, String named) {
        RUNNER.withPropertyValues(setting.strip())
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(named.strip()));
    }

    /** An empty value is no value: the default. */
    @Test
    void anEmptyVisibilityKeepsTheDefault() {
        RUNNER.withPropertyValues("application.privacy.coworker-visibility=")
                .run(context -> assertThat(context.getBean(ApplicationProperties.class).getPrivacy()
                        .getCoworkerVisibility()).isEqualTo(CoworkerVisibility.FULL));
    }
}
