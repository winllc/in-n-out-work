package com.winllc.innoutwork.config;

import com.winllc.innoutwork.security.AppUserDetailsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * That demo mode stays off.
 *
 * <p>The direction that matters. Demo mode removes authentication from the whole application, so the
 * question is not only whether it works when asked for, but whether anything short of asking for it
 * can bring it about.
 */
class DemoModeDisabledTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(AppUserDetailsService.class, () -> mock(AppUserDetailsService.class))
                .withBean(ApplicationProperties.class, ApplicationProperties::new)
                .withUserConfiguration(DemoSecurityConfig.class);
    }

    @Test
    void noPropertyMeansNoDemoChain() {
        runner().run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(SecurityFilterChain.class));
    }

    @Test
    void explicitlyFalseMeansNoDemoChain() {
        runner().withPropertyValues("application.demo.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SecurityFilterChain.class));
    }

    /**
     * Only a boolean true turns it on, so a value that merely looks affirmative cannot. ("TRUE" and
     * "True" do enable it, as they should - the property is a boolean.)
     */
    @Test
    void anythingOtherThanTrueMeansNoDemoChain() {
        for (String value : new String[]{"yes", "1", "on", "", "false", "no"}) {
            runner().withPropertyValues("application.demo.enabled=" + value)
                    .run(context -> assertThat(context).doesNotHaveBean(SecurityFilterChain.class));
        }
    }

    /** A user DN on its own does nothing; the switch is the switch. */
    @Test
    void aConfiguredDemoUserAloneDoesNotEnableIt() {
        runner().withPropertyValues("application.demo.user-dn=cn=Demo,dc=winllc,dc=com")
                .run(context -> assertThat(context).doesNotHaveBean(SecurityFilterChain.class));
    }
}
