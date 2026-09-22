package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.LdapTemplate;

import javax.naming.directory.SearchControls;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a user's group memberships are cached.
 *
 * <p>The lookup behind this cache is a reverse-membership search over the whole group tree, which is
 * the slowest thing the profile page does. What matters is not only that it is cached, but that a
 * slow one cannot take the page down with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapServiceGroupMembershipTest {

    private static final String BASE = "dc=example,dc=com";
    private static final String BOB = "cn=bob,ou=users," + BASE;

    @Mock
    private LdapTemplate ldapTemplate;

    private ApplicationProperties props;
    private LdapService ldapService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        props = new ApplicationProperties();
        props.setUserBaseDn(BASE);
        // Straight to ldapTemplate.search, so the search itself can be made to block.
        props.getLdap().setPageSize(0);

        TopLevelGroupProperties groups = new TopLevelGroupProperties();
        groups.setGroupsBaseDn("ou=groups," + BASE);
        props.setGroups(List.of(groups));

        ldapService = new LdapService(ldapTemplate, props);
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private void stubSearch(List<LdapGroup> result) {
        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenReturn(result);
    }

    @Test
    void membershipIsReadOnceWithinTheCachePeriod() {
        stubSearch(List.of());

        ldapService.findGroupsForUser(BOB);
        ldapService.findGroupsForUser(BOB);

        verify(ldapTemplate, times(1))
                .search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSlowLookupDoesNotBlockAnotherRequestForTheSameUser() throws Exception {
        CountDownLatch searchStarted = new CountDownLatch(1);
        CountDownLatch releaseSearch = new CountDownLatch(1);

        when(ldapTemplate.search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class)))
                .thenAnswer(invocation -> {
                    searchStarted.countDown();
                    releaseSearch.await(10, TimeUnit.SECONDS);
                    return List.of();
                })
                .thenReturn(List.of());

        Future<List<LdapGroup>> slow = executor.submit(() -> ldapService.findGroupsForUser(BOB));
        assertTrue(searchStarted.await(5, TimeUnit.SECONDS), "the first lookup never reached the directory");

        // Caching through get(key, mappingFunction) would run this under computeIfAbsent, and this
        // second call for the same key would sit behind the stuck one - which is why reloading the
        // profile page never recovered it.
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> ldapService.findGroupsForUser(BOB),
                "a second request for the same user blocked behind the slow lookup");

        releaseSearch.countDown();
        slow.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theCachedResultIsServedOnceTheLookupCompletes() {
        stubSearch(List.of());

        assertEquals(List.of(), ldapService.findGroupsForUser(BOB));
        assertEquals(List.of(), ldapService.findGroupsForUser(BOB));
    }

    @Test
    void turningTheCacheOffSearchesEveryTime() {
        props.getLdap().setGroupMembershipCacheSeconds(0);
        LdapService uncached = new LdapService(ldapTemplate, props);
        stubSearch(List.of());

        uncached.findGroupsForUser(BOB);
        uncached.findGroupsForUser(BOB);

        verify(ldapTemplate, times(2))
                .search(anyString(), anyString(), any(SearchControls.class), any(ContextMapper.class));
    }
}
