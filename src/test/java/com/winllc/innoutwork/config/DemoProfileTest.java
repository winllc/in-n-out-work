package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo profile and the account it names.
 *
 * <p>The profile is only useful if the DN it presents the application as is one the seed script
 * actually creates and the config actually treats as an administrator. Those three live in three
 * different files, in two different languages, and nothing but this test connects them - rename the
 * account in one and the demo silently comes up as an identity the directory cannot resolve.
 */
class DemoProfileTest {

    private static final Path SEED_SCRIPT = Path.of("test", "seed-mock-data.sh");
    private static final Path CONTAINER_CONFIG = Path.of("test", "application.yml");

    private static ConfigurableEnvironment environment(String... profiles) {
        ConfigurableEnvironment env = new StandardEnvironment();
        if (profiles.length > 0) {
            env.setActiveProfiles(profiles);
        }
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), null, List.of(profiles));
        return env;
    }

    /** "CN=Demo Admin,OU=..." -> "Demo Admin", which is how the seed script and LDAP name it. */
    private static String commonNameOf(String dn) {
        String first = dn.split(",")[0];
        return first.substring(first.indexOf('=') + 1).trim();
    }

    @Test
    void theDemoProfileTurnsDemoModeOnAndNamesAnAccount() {
        ConfigurableEnvironment env = environment("demo");

        assertEquals("true", env.getProperty("application.demo.enabled"));
        String userDn = env.getProperty("application.demo.user-dn");
        assertNotNull(userDn);
        assertFalse(userDn.isBlank(), "the demo profile must name the account it presents");
    }

    @Test
    void withoutTheProfileDemoModeStaysOffAndNamesNobody() {
        ConfigurableEnvironment env = environment();

        assertEquals("false", env.getProperty("application.demo.enabled"));
        // Blank on purpose: enabling demo mode without supplying a DN refuses to start, and a
        // default here would quietly remove that.
        assertTrue(env.getProperty("application.demo.user-dn", "").isBlank());
    }

    @Test
    void theSeedScriptCreatesTheAccountTheDemoProfileNames() throws IOException {
        String cn = commonNameOf(environment("demo").getProperty("application.demo.user-dn"));

        String seed = Files.readString(SEED_SCRIPT);
        assertTrue(seed.contains("\"" + cn + "|"),
                "test/seed-mock-data.sh has no user row for '" + cn + "', so the demo would come up "
                        + "as a DN the directory cannot resolve");
    }

    @Test
    void theAccountTheDemoProfileNamesIsASuperUser() throws IOException {
        String userDn = environment("demo").getProperty("application.demo.user-dn");

        String config = Files.readString(CONTAINER_CONFIG);
        assertTrue(config.toLowerCase().contains(userDn.toLowerCase()),
                "test/application.yml does not list " + userDn + " under super-user-dns, so signing "
                        + "in as it normally would not be an administrator");
    }
}
