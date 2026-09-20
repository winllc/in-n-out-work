package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.service.loader.LdapGroupLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Group trees are cached one DN at a time, but they are built a whole subtree at a time.
 * These cover the two places that gap used to leak: descendants of a loaded tree being
 * thrown away, and a tree the directory failed to finish being cached as if complete.
 */
@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    private static final String ROOT = "ou=groups,dc=example,dc=com";
    private static final String CHILD = "cn=engineering,ou=groups,dc=example,dc=com";
    private static final String GRANDCHILD = "cn=platform,cn=engineering,ou=groups,dc=example,dc=com";

    @Mock
    private LdapService ldapService;

    @Mock
    private LoadingCache<String, Long> countCache;

    private LoadingCache<String, LdapGroup> groupCache;
    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        groupCache = Caffeine.newBuilder()
                .maximumSize(5000)
                .build(new LdapGroupLoader(ldapService));

        cacheService = new CacheService(groupCache, countCache);
    }

    /** root -> child -> grandchild, the shape a recursive walk produces. */
    private LdapGroup tree() {
        LdapGroup root = new LdapGroup(ROOT, "groups");
        LdapGroup child = new LdapGroup(CHILD, "engineering");
        LdapGroup grandchild = new LdapGroup(GRANDCHILD, "platform");

        child.addChild(grandchild);
        root.addChild(child);

        return root;
    }

    @Test
    void loadingATreeCachesEveryGroupUnderneathIt() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());

        cacheService.getGroup(ROOT);

        // Without warming, only ROOT would be here and every group below it would be a
        // cold miss that repeated the whole walk.
        assertTrue(groupCache.asMap().containsKey(ROOT));
        assertTrue(groupCache.asMap().containsKey(CHILD));
        assertTrue(groupCache.asMap().containsKey(GRANDCHILD));
    }

    @Test
    void aGroupBelowTheRootIsServedWithoutWalkingTheDirectoryAgain() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());

        cacheService.getGroup(ROOT);
        LdapGroup child = cacheService.getGroup(CHILD);

        assertNotNull(child);
        assertEquals("engineering", child.getName());
        verify(ldapService, never()).buildGroupRecursiveInternal(CHILD);
    }

    @Test
    void warmingDoesNotDisplaceAGroupAlreadyCachedInItsOwnRight() {
        LdapGroup established = new LdapGroup(CHILD, "engineering");
        groupCache.put(CHILD, established);

        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());

        cacheService.getGroup(ROOT);

        // putIfAbsent, so the existing entry keeps its own write time and stays on its
        // original refresh and expiry schedule.
        assertSame(established, groupCache.getIfPresent(CHILD));
    }

    @Test
    void servingAGroupAgainDoesNotRepeatTheWalk() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());

        cacheService.getGroup(ROOT);
        cacheService.getGroup(ROOT);

        verify(ldapService, times(1)).buildGroupRecursiveInternal(ROOT);
    }

    @Test
    void aTreeTheDirectoryFailedToFinishIsServedButNotCached() {
        LdapGroup partial = new LdapGroup(ROOT, "groups");
        when(ldapService.buildGroupRecursiveInternal(ROOT))
                .thenThrow(new GroupTreeIncompleteException(ROOT, partial, new RuntimeException("connection reset")));

        LdapGroup served = cacheService.getGroup(ROOT);

        assertSame(partial, served);
        // The whole point: a transient failure must not pin a childless tree for the
        // length of the expiry window.
        assertNull(groupCache.getIfPresent(ROOT));
    }

    @Test
    void theNextRequestAfterAFailureGoesBackToTheDirectory() {
        LdapGroup partial = new LdapGroup(ROOT, "groups");
        when(ldapService.buildGroupRecursiveInternal(ROOT))
                .thenThrow(new GroupTreeIncompleteException(ROOT, partial, new RuntimeException("connection reset")))
                .thenReturn(tree());

        cacheService.getGroup(ROOT);
        LdapGroup recovered = cacheService.getGroup(ROOT);

        assertNotNull(recovered);
        assertEquals(1, recovered.getChildren().size());
        verify(ldapService, times(2)).buildGroupRecursiveInternal(ROOT);
        assertTrue(groupCache.asMap().containsKey(CHILD));
    }

    @Test
    void evictingAGroupTakesTheGroupsUnderneathItToo() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);

        int evicted = cacheService.evictGroup(CHILD);

        // All three: the grandchild is cached under its own key and would otherwise be
        // served as it was before the change, and the root's tree embeds both.
        assertEquals(3, evicted);
        assertNull(groupCache.getIfPresent(CHILD));
        assertNull(groupCache.getIfPresent(GRANDCHILD));
    }

    @Test
    void evictingAGroupTakesTheTreesThatContainItToo() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);

        cacheService.evictGroup(GRANDCHILD);

        // The root and the child hold the very same grandchild object inside their trees,
        // so keeping them would go on serving the stale copy from every ancestor.
        assertNull(groupCache.getIfPresent(ROOT));
        assertNull(groupCache.getIfPresent(CHILD));
        assertNull(groupCache.getIfPresent(GRANDCHILD));
    }

    @Test
    void evictingAGroupLeavesUnrelatedTreesAlone() {
        String otherRoot = "ou=departments,dc=example,dc=com";
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);
        groupCache.put(otherRoot, new LdapGroup(otherRoot, "departments"));

        cacheService.evictGroup(CHILD);

        assertNotNull(groupCache.getIfPresent(otherRoot));
    }

    @Test
    void aGroupWhoseNameMerelyStartsTheSameIsNotEvicted() {
        String lookalike = "cn=engineering-archive,ou=groups,dc=example,dc=com";
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);
        groupCache.put(lookalike, new LdapGroup(lookalike, "engineering-archive"));

        cacheService.evictGroup(CHILD);

        // "cn=engineering-archive,..." shares a prefix with "cn=engineering,..." but is a
        // different group; matching has to land on an RDN boundary.
        assertNotNull(groupCache.getIfPresent(lookalike));
    }

    @Test
    void anEvictedGroupIsWalkedAgainOnTheNextRequest() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());

        cacheService.getGroup(ROOT);
        cacheService.evictGroup(ROOT);
        cacheService.getGroup(ROOT);

        verify(ldapService, times(2)).buildGroupRecursiveInternal(ROOT);
    }

    @Test
    void aDnIsMatchedHoweverItIsSpacedOrCased() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);

        // Same DN as CHILD, retyped the way someone would paste it out of a directory tool.
        int evicted = cacheService.evictGroup("CN=Engineering, OU=Groups, DC=example, DC=com");

        assertEquals(3, evicted);
        assertNull(groupCache.getIfPresent(CHILD));
    }

    @Test
    void evictingEverythingEmptiesTheCache() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);

        int evicted = cacheService.evictAllGroups();

        assertEquals(3, evicted);
        assertEquals(0, cacheService.cachedGroupCount());
    }

    @Test
    void evictingADnThatIsNotCachedIsHarmless() {
        when(ldapService.buildGroupRecursiveInternal(ROOT)).thenReturn(tree());
        cacheService.getGroup(ROOT);

        assertEquals(0, cacheService.evictGroup("cn=nowhere,ou=other,dc=example,dc=com"));
        assertEquals(0, cacheService.evictGroup(""));
        assertEquals(3, cacheService.cachedGroupCount());
    }

    @Test
    void anUnresolvableDnYieldsNullRatherThanAnEmptyGroup() {
        when(ldapService.buildGroupRecursiveInternal(anyString())).thenReturn(null);

        assertNull(cacheService.getGroup(ROOT));
        assertNull(groupCache.getIfPresent(ROOT));
    }
}
