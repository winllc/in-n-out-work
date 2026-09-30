package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo profile and the accounts it publishes.
 *
 * <p>The tile is only useful if the credentials on it work and the role beside each one is true.
 * Neither is checkable from the login page: the accounts are configured in one file, created by a
 * shell script in another, and given their roles by a third. Nothing but this test connects them, so
 * renaming an account in one place leaves a tile that invites visitors to sign in as something that
 * does not exist.
 */
class DemoProfileTest {

    private static final Path SEED_SCRIPT = Path.of("test", "seed-mock-data.sh");
    private static final Path PACKAGED_CONFIG = Path.of("src", "main", "resources", "application.yml");

    private static ConfigurableEnvironment environment(String... profiles) {
        ConfigurableEnvironment env = new StandardEnvironment();
        if (profiles.length > 0) {
            env.setActiveProfiles(profiles);
        }
        ConfigDataEnvironmentPostProcessor.applyTo(env, new DefaultResourceLoader(), null, List.of(profiles));
        return env;
    }

    private static ApplicationProperties.Demo demo(String... profiles) {
        return Binder.get(environment(profiles))
                .bind("application.demo", ApplicationProperties.Demo.class)
                .orElseGet(ApplicationProperties.Demo::new);
    }

    @Test
    void theDemoProfileTurnsDemoModeOnAndPublishesAccounts() {
        ApplicationProperties.Demo settings = demo("demo");

        assertTrue(settings.isEnabled());
        assertFalse(settings.getAccounts().isEmpty(), "the tile would be empty");
    }

    @Test
    void withoutTheProfileDemoModeStaysOffAndPublishesNothing() {
        ApplicationProperties.Demo settings = demo();

        assertFalse(settings.isEnabled());
        // A configured account on a deployment that never asked for demo mode would be a published
        // password, so the default has to be empty as well as off.
        assertTrue(settings.getAccounts().isEmpty());
    }

    @Test
    void everyPublishedAccountIsComplete() {
        for (ApplicationProperties.DemoAccount account : demo("demo").getAccounts()) {
            assertFalse(account.getRole().isBlank(), "an account with no role label");
            assertFalse(account.getUsername().isBlank(), "an account with no username");
            assertFalse(account.getPassword().isBlank(), "an account with no password");
        }
    }

    /** Each username has to be a uid the seed script actually creates, or the tile cannot sign in. */
    @Test
    void everyPublishedUsernameIsSeeded() throws IOException {
        String seed = Files.readString(SEED_SCRIPT);

        for (ApplicationProperties.DemoAccount account : demo("demo").getAccounts()) {
            assertTrue(seed.contains("|" + account.getUsername() + "|"),
                    "test/seed-mock-data.sh creates no user with uid '" + account.getUsername()
                            + "', so the login page offers credentials that cannot work");
        }
    }

    /** The passwords on the tile have to be the one the seed script sets on every mock user. */
    @Test
    void thePublishedPasswordIsTheOneTheSeedSets() throws IOException {
        String seed = Files.readString(SEED_SCRIPT);
        assertTrue(seed.contains("userPassword: password"), "the seed script's password changed");

        for (ApplicationProperties.DemoAccount account : demo("demo").getAccounts()) {
            assertEquals("password", account.getPassword(),
                    "the seeded accounts all share one password; " + account.getUsername() + " differs");
        }
    }

    /** Administrator has to come from super-user-dns, and Manager from the row the seed writes. */
    @Test
    void theAdvertisedRolesAreOnesTheDataActuallyGrants() throws IOException {
        String config = Files.readString(PACKAGED_CONFIG);
        String seed = Files.readString(SEED_SCRIPT);

        assertTrue(config.contains("CN=Demo Admin,OU=Users,DC=winllc,DC=com"),
                "the administrator account is not under super-user-dns, so it would not be an admin");
        assertTrue(seed.contains("'MANAGER'"),
                "the seed script grants nobody the MANAGER role, so the tile's manager would be a plain user");
    }
}
