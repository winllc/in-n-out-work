package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * The one cache in front of the directory's group tree.
 *
 * <p>Group trees used to be cached twice — here, and again by {@code @Cacheable} on the
 * builder in {@link LdapService}. The two layers had different refresh settings and the
 * lower one answered the upper one's refreshes, so a refresh usually returned the same
 * stale tree it already held. There is now a single layer, and it is this one.
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final LoadingCache<String, LdapGroup> cache;
    private final LoadingCache<String, Long> ldapCountLoadingCache;

    public CacheService(@Qualifier("ldapGroupLoadingCache")
                                 LoadingCache<String, LdapGroup> cache,
                        @Qualifier("ldapCountLoadingCache")
                        LoadingCache<String, Long> ldapCountLoadingCache) {
        this.cache = cache;
        this.ldapCountLoadingCache = ldapCountLoadingCache;
    }

    public LdapGroup getGroup(String dn) {
        // Read before the get so a load can be told apart from a hit. Only a load has a
        // freshly walked tree worth warming, and re-warming on every hit would keep
        // pushing the descendants' write times forward and hold their expiry off forever.
        boolean alreadyCached = cache.asMap().containsKey(dn);

        LdapGroup group;
        try {
            group = cache.get(dn); // triggers load or async refresh
        } catch (GroupTreeIncompleteException e) {
            return servePartial(dn, e);
        } catch (CompletionException e) {
            // Caffeine rethrows an unchecked loader failure as-is, but wraps it when the
            // load ran on another thread. Same failure either way.
            if (e.getCause() instanceof GroupTreeIncompleteException cause) {
                return servePartial(dn, cause);
            }
            throw e;
        }

        if (group == null) {
            // The loader returns null for a DN the directory cannot resolve. Callers must
            // handle this; it is the more interesting of the two outcomes.
            log.warn("No group found in the directory for {}", dn);
            return null;
        }

        if (!alreadyCached) {
            warmDescendants(group);
        }

        log.debug("Serving group {} ({} child groups)", dn, childrenOf(group).size());

        return group;
    }

    /**
     * Drops a group from the cache so the next request rebuilds it from the directory.
     *
     * <p>Evicts more than the one entry, because one entry on its own would not take
     * effect. Groups below the DN are cached under their own keys and would stay behind
     * as the versions they were when the tree was walked; groups above it hold the very
     * same child objects inside their own trees, so a stale copy would go on being served
     * from every ancestor. Both directions go.
     *
     * @return how many cache entries were dropped
     */
    public int evictGroup(String dn) {
        String target = comparable(dn);

        if (target == null || target.isBlank()) {
            return 0;
        }

        List<String> doomed = cache.asMap().keySet().stream()
                .filter(key -> related(comparable(key), target))
                .toList();

        doomed.forEach(cache::invalidate);

        log.info("Evicted {} cached group entr{} for {}", doomed.size(), doomed.size() == 1 ? "y" : "ies", dn);

        return doomed.size();
    }

    /** Drops every cached group tree. The next request for each walks the directory again. */
    public int evictAllGroups() {
        int evicted = cache.asMap().size();

        cache.invalidateAll();

        log.info("Evicted all {} cached group entries", evicted);

        return evicted;
    }

    /** How many group trees are cached right now, for the settings page to report. */
    public int cachedGroupCount() {
        return cache.asMap().size();
    }

    public Long getLdapCount(String dn) {
        Long count = ldapCountLoadingCache.get(dn);

        log.debug("Serving entry count {} for {}", count, dn);

        return count;
    }

    /**
     * Caches every descendant of a freshly loaded tree under its own DN.
     *
     * <p>The walk that produced this tree already built them all. Without this they are
     * discarded, and because {@code /app/users/{group}} looks groups up one DN at a time,
     * the first visit to any group below a configured top-level DN was a cold miss that
     * repeated the entire recursive walk for that subtree.
     */
    private void warmDescendants(LdapGroup root) {
        Deque<LdapGroup> pending = new ArrayDeque<>(childrenOf(root));
        int warmed = 0;

        while (!pending.isEmpty()) {
            LdapGroup group = pending.pop();

            // putIfAbsent, so a group already cached in its own right keeps the write time
            // it has and its refresh and expiry stay on their original schedule.
            if (group.getDn() != null && cache.asMap().putIfAbsent(group.getDn(), group) == null) {
                warmed++;
            }

            pending.addAll(childrenOf(group));
        }

        log.debug("Warmed {} descendant group(s) from the tree at {}", warmed, root.getDn());
    }

    private LdapGroup servePartial(String dn, GroupTreeIncompleteException e) {
        // Rendered for this request, deliberately not cached, so the next request goes
        // back to the directory rather than serving a half tree until it expires.
        log.warn("Serving an uncached partial group tree for {}: {}", dn, e.getMessage());

        return e.getPartial();
    }

    /**
     * Whether a cached key is the target, sits under it, or is a tree that contains it.
     *
     * <p>Both DNs are already normalised and upper-cased, so the containment test is a
     * plain suffix match on RDN boundaries.
     */
    private static boolean related(String key, String target) {
        if (key == null) {
            return false;
        }

        return key.equals(target)
                || key.endsWith("," + target)   // key sits under the target
                || target.endsWith("," + key);  // key is a tree the target sits under
    }

    /** DNs in the form this compares them in: normalised, then upper-cased as LdapDn does. */
    private static String comparable(String dn) {
        String normalized = LdapDn.normalize(dn);

        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static List<LdapGroup> childrenOf(LdapGroup group) {
        return group.getChildren() != null ? group.getChildren() : List.of();
    }
}
