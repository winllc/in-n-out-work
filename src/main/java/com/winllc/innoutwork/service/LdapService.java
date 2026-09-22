package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.control.PagedResultsCookie;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.SingleContextSource;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.Filter;
import org.springframework.ldap.filter.HardcodedFilter;
import org.springframework.ldap.filter.PresentFilter;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.ldap.query.SearchScope;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

@Service
public class LdapService {

    private static final Logger log = LoggerFactory.getLogger(LdapService.class);

    /** Requests no attributes, only entry names (RFC 4511 4.5.1.8). */
    static final String[] NO_ATTRIBUTES = {"1.1"};
    /** What LdapGroupContextMapper reads. Left unrestricted, every search returns each group's full member list. */
    static final String[] GROUP_ATTRIBUTES = {"cn", "description", "owner", "distinguishedName"};
    private static final String[] MEMBER_ATTRIBUTES = {"uniqueMember"};

    private final LdapTemplate ldapTemplate;
    private final ApplicationProperties properties;
    /** Each user's groups by lower-cased DN; null when the cache is turned off. */
    private final Cache<String, List<LdapGroup>> groupsForUserCache;


    public LdapService(LdapTemplate ldapTemplate,
                       ApplicationProperties properties) {
        this.ldapTemplate = ldapTemplate;
        this.properties = properties;

        int cacheSeconds = properties.getLdap().getGroupMembershipCacheSeconds();
        this.groupsForUserCache = cacheSeconds > 0
                ? Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(cacheSeconds))
                        .maximumSize(10_000)
                        .build()
                : null;
    }

    /** The attributes LdapUserContextMapper reads, from configuration. */
    String[] userAttributes() {
        return Stream.of(
                        properties.getUserLdapOrganizationAttribute(),
                        properties.getUserLdapEmployeeTypeAttribute(),
                        properties.getUserLdapLocationAttribute(),
                        properties.getUserLdapBranchAttribute(),
                        properties.getUserLdapManagerIdAttribute(),
                        properties.getManagerLdapIdAttribute(),
                        properties.getUserLdapEmailAttribute(),
                        properties.getUserLdapPhoneAttribute(),
                        properties.getUserLdapDutySubOrganizationAttribute())
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }

    /**
     * A search result's attributes. Results are DirContextAdapters when JNDI can load Spring LDAP's object
     * factory, and raw DirContexts (LdapCtx) when it can't, e.g. on a thread whose context class loader
     * doesn't see the application's jars; see CacheConfig.REFRESH_EXECUTOR.
     */
    static Attributes attributesOf(Object ctx) throws NamingException {
        if (ctx instanceof DirContextAdapter c) {
            return c.getAttributes();
        } else if (ctx instanceof DirContext c) {
            return c.getAttributes("");
        }
        throw new IllegalArgumentException("Unsupported: " + ctx.getClass());
    }

    /** A search result's absolute name; see {@link #attributesOf}. */
    static String nameInNamespaceOf(Object ctx) throws NamingException {
        if (ctx instanceof DirContext c) { // DirContextAdapter is one too
            return c.getNameInNamespace();
        }
        throw new IllegalArgumentException("Unsupported: " + ctx.getClass());
    }

    private static SearchControls subtree(String... attributes) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(attributes);
        return controls;
    }

    /**
     * A search that reads every page of results on one connection. Without paging a directory stops at its
     * size limit and, because the template ignores size-limit errors, the rest are silently missing.
     */
    <T> List<T> pagedSearch(String base, String filter, SearchControls controls, ContextMapper<T> mapper) {
        int pageSize = properties.getLdap().getPageSize();
        if (pageSize <= 0) {
            return ldapTemplate.search(base, filter, controls, mapper);
        }

        int maxPages = Math.max(1, properties.getLdap().getMaxPages());

        return SingleContextSource.doWithSingleContext(ldapTemplate.getContextSource(), operations -> {
            PagedResultsDirContextProcessor processor = new PagedResultsDirContextProcessor(pageSize);
            List<T> results = new ArrayList<>();
            int pages = 0;
            PagedResultsCookie previousCookie = null;

            do {
                results.addAll(operations.search(base, filter, controls, mapper, processor));
                pages++;

                if (!processor.hasMore()) {
                    break;
                }

                // A cookie identical to the last one means the server is not advancing, so the next
                // page would repeat this one for ever. Seen with referrals and with proxies that
                // accept the paged results control without honouring it.
                PagedResultsCookie cookie = processor.getCookie();
                if (previousCookie != null && previousCookie.equals(cookie)) {
                    log.warn("Search under {} for {} stopped after {} page(s): the directory returned the "
                            + "same paging cookie twice, so results may be incomplete", base, filter, pages);
                    break;
                }
                previousCookie = cookie;

                if (pages >= maxPages) {
                    // Bounded rather than unbounded: holding the request open indefinitely is worse
                    // than returning a truncated answer and saying so.
                    log.warn("Search under {} for {} hit the {}-page limit after {} entries; returning what "
                            + "was read. Raise application.ldap.max-pages if this result set is genuinely "
                            + "this large", base, filter, maxPages, results.size());
                    break;
                }
            } while (true);

            log.trace("Search under {} for {} read {} entries in {} page(s)", base, filter, results.size(), pages);
            return results;
        }, true, false, false); // read-only context, like the template's own searches; errors propagate as they do there
    }

    /**
     * Recursively translates an LDAP group and all nested groups (via 'seeAlso') into Java objects.
     *
     * @param dn distinguished name (DN) of the root group
     * @return hierarchical LdapGroup object
     */
    public LdapGroup getGroupHierarchyFromAttribute(String dn) {
        return buildGroupHierarchyFromAttribute(dn, new ArrayList<>());
    }

    /**
     * Counts LDAP entries under a top-level group's base DN that carry a given attribute value,
     * e.g. how many users have {@code branch=NORTH}.
     *
     * @param attribute the attribute name to match on (e.g. "branch")
     * @param value     the attribute value to match (matched exactly; encoded to prevent injection)
     * @return the number of matching entries, or {@code 0} if inputs are missing or the search fails
     */
    public Map<String, Integer> getTotalEntriesWithAttributeValueSplitOnAttribute(String baseDn, String attribute, String value,
                                                                 String splitByAttribute) {
        if (baseDn == null || baseDn.isBlank() || attribute == null || attribute.isBlank() || value == null) {
            return Collections.emptyMap();
        }

        // EqualsFilter encodes the value, guarding against LDAP injection via the value parameter.
        EqualsFilter filter = new EqualsFilter(attribute, value);

        try {
            return countWithSplit(baseDn, filter.encode(), splitByAttribute);
        } catch (Exception e) {
            log.error("Failed to count entries under {} where {}={}",
                    baseDn, attribute, value, e);
            return Collections.emptyMap();
        }
    }

    /**
     * For every value of {@code attribute} under {@code baseDn}, how many entries have it, split by
     * {@code splitByAttribute}: the counts {@link #getTotalEntriesWithAttributeValueSplitOnAttribute} gives
     * for one value, for all values in a single scan. Values are matched ignoring case, as the directory's
     * equality match does; an entry with several values counts under each.
     */
    public Map<String, Map<String, Integer>> countByAttributeValueSplitOnAttribute(String baseDn, String attribute,
                                                                                   String splitByAttribute) {
        Map<String, Map<String, Integer>> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (baseDn == null || baseDn.isBlank() || attribute == null || attribute.isBlank()) {
            return counts;
        }

        long start = System.currentTimeMillis();
        SearchControls controls = StringUtils.isBlank(splitByAttribute) ? subtree(attribute) : subtree(attribute, splitByAttribute);
        List<Map.Entry<List<String>, String>> rows = pagedSearch(baseDn, new PresentFilter(attribute).encode(), controls,
                (ContextMapper<Map.Entry<List<String>, String>>) ctx -> {
                    Attributes attrs = attributesOf(ctx);
                    List<String> values = new ArrayList<>();
                    Attribute valueAttr = attrs.get(attribute);
                    if (valueAttr != null) {
                        NamingEnumeration<?> all = valueAttr.getAll();
                        try {
                            while (all.hasMore()) {
                                values.add(all.next().toString());
                            }
                        } finally {
                            all.close();
                        }
                    }
                    Attribute split = StringUtils.isBlank(splitByAttribute) ? null : attrs.get(splitByAttribute);
                    return Map.entry(values, split != null ? split.get().toString() : "EMPTY");
                });

        for (Map.Entry<List<String>, String> row : rows) {
            Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String value : row.getKey()) {
                if (seen.add(value)) {
                    counts.computeIfAbsent(value, k -> new HashMap<>()).merge(row.getValue(), 1, Integer::sum);
                }
            }
        }

        log.debug("Counted {} entries under {} into {} value(s) of {} in {}ms",
                rows.size(), baseDn, counts.size(), attribute, System.currentTimeMillis() - start);
        return counts;
    }

    // Alternative: More efficient approach that doesn't iterate through all previous pages
    public List<UserStatus> search(String filter) {
        // Only the names are used; callers look each row's status up by DN.
        return pagedSearch(
                properties.getUserBaseDn(),
                filter,
                subtree(NO_ATTRIBUTES),
                (ContextMapper<UserStatus>) ctx -> {
                    // getDn() is relative to the context source's base (spring.ldap.base), so it
                    // is only absolute while that base is unset. Callers re-read every row by DN
                    // for its status, notes and details link, so take the absolute name, matching
                    // how LdapUserContextMapper maps a DN.
                    UserStatus user = UserStatus.builder()
                            .dn(LdapDn.normalize(nameInNamespaceOf(ctx)))
                            .build();
                    return user;
                }
        );

    }

    public List<LdapUser> searchUsers(LdapQuery query) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope((query.searchScope() != null ? query.searchScope() : SearchScope.SUBTREE).getId());
        if (query.countLimit() != null) {
            controls.setCountLimit(query.countLimit());
        }
        controls.setReturningAttributes(query.attributes() != null ? query.attributes() : userAttributes());

        String base = query.base() != null ? query.base().toString() : "";
        String filter = query.filter().encode();
        return query.countLimit() != null
                ? ldapTemplate.search(base, filter, controls, new LdapUserContextMapper(properties))
                : pagedSearch(base, filter, controls, new LdapUserContextMapper(properties));
    }

    /**
     * Every user entry under the configured base DN, in a single subtree search.
     * <p>
     * This is the bulk counterpart to {@link #lookupUser(LdapDn)}: the refresh job needs
     * the whole population, and doing that one DN at a time is a round trip per user.
     *
     * @return all mapped users; empty when no base DN is configured
     */
    public List<LdapUser> findAllUsers() {
        if (StringUtils.isBlank(properties.getUserBaseDn())) {
            log.warn("No user base DN configured; cannot enumerate directory users");
            return List.of();
        }

        long start = System.currentTimeMillis();

        LdapQuery query = LdapQueryBuilder.query()
                .base(properties.getUserBaseDn())
                .searchScope(SearchScope.SUBTREE)
                .filter(properties.getUserLdapFilter());

        List<LdapUser> users = searchUsers(query);

        log.debug("Enumerated {} user entries under {} (filter {}) in {}ms",
                users.size(), properties.getUserBaseDn(), properties.getUserLdapFilter(),
                System.currentTimeMillis() - start);

        return users;
    }

    /**
     * Finds the users who report to the given manager id.
     * <p>
     * The directory models the relationship with a pair of attributes: a manager carries their own
     * id in {@code managerLdapIdAttribute}, and each of their reports carries that same value in
     * {@code userLdapManagerIdAttribute}. So the reports of a manager are the users whose
     * {@code userLdapManagerIdAttribute} equals the manager id passed in here.
     *
     * @param managerId the manager's own id; a blank value returns an empty list rather than matching everyone
     * @return the matching users, never {@code null}
     */
    public List<LdapUser> findUsersReportingTo(String managerId) {
        if (StringUtils.isBlank(managerId)) {
            return new ArrayList<>();
        }

        // The configured filter already carries its own parentheses (ApplicationProperties
        // normalises it), so this only supplies the ones the AND itself needs.
        String filter = "(&%s(%s=%s))".formatted(
                properties.getUserLdapFilter(),
                properties.getUserLdapManagerIdAttribute(),
                escapeLdapFilter(managerId));

        try {
            return pagedSearch(properties.getUserBaseDn(), filter, subtree(userAttributes()),
                    new LdapUserContextMapper(properties));
        } catch (Exception e) {
            log.error("Failed to look up reports for manager id {}", managerId, e);
            return new ArrayList<>();
        }
    }

    public Optional<LdapUser> lookupUser(LdapDn dn) {
        LdapUser user = null;
        try {

            if (properties.isLookupOnDnAttribute()) {
                LdapQuery query = LdapQueryBuilder.query()
                        .base(properties.getUserBaseDn())
                        .countLimit(1)
                        .attributes(userAttributes())
                        .filter(new EqualsFilter(properties.getUserDnAttribute(), dn.toString()));

                List<LdapUser> users = ldapTemplate.search(query, new LdapUserContextMapper(properties));

                if (!CollectionUtils.isEmpty(users)) {
                    user = users.getFirst();
                }

            } else {
                user = ldapTemplate.lookup(dn.toString(), userAttributes(), new LdapUserContextMapper(properties));
            }

        } catch (Exception e) {
            log.error("Not found: %s".formatted(dn), e);
        }

        return Optional.ofNullable(user);
    }

    /**
     * The one user entry (matching the configured user filter) whose {@code attribute} equals {@code value};
     * empty when there is none, or more than one, since then it is not clear who is meant.
     */
    public Optional<LdapUser> lookupUniqueUser(String attribute, String value) {
        AndFilter filter = new AndFilter();
        if (StringUtils.isNotBlank(properties.getUserLdapFilter())) {
            filter.and(new HardcodedFilter(properties.getUserLdapFilter()));
        }
        filter.and(new EqualsFilter(attribute, value));

        LdapQuery query = LdapQueryBuilder.query()
                .base(properties.getUserBaseDn())
                .countLimit(2)
                .attributes(userAttributes())
                .filter(filter);

        List<LdapUser> users = ldapTemplate.search(query, new LdapUserContextMapper(properties));
        if (users.size() > 1) {
            log.warn("More than one directory user has {}={}; not choosing between them", attribute, value);
            return Optional.empty();
        }
        return users.stream().findFirst();
    }

    public Optional<LdapUser> lookupUser(String attribute, String value) {
        LdapQuery query = LdapQueryBuilder.query()
                .base(properties.getUserBaseDn())
                .countLimit(1)
                .attributes(userAttributes())
                .filter(new EqualsFilter(attribute, value));

        List<LdapUser> users = ldapTemplate.search(query, new LdapUserContextMapper(properties));
        if (!CollectionUtils.isEmpty(users)) {
            return Optional.of(users.getFirst());
        } else {
            return Optional.empty();
        }
    }

    public long count(String baseDn, String filter) {
        long start = System.currentTimeMillis();
        // Names only; an empty attribute list would ask for every attribute.
        List<Boolean> results = pagedSearch(baseDn, filter, subtree(NO_ATTRIBUTES), (ContextMapper<Boolean>) ctx -> Boolean.TRUE);

        log.debug("Counted {} entries under {} matching {} in {}ms",
                results.size(), baseDn, filter, System.currentTimeMillis() - start);

        return results.size();
    }

    public Map<String, Integer> countWithSplit(String baseDn, String filter, String splitByAttribute) {
        // Only the split attribute. (A blank base used to request no attributes, so every entry counted as EMPTY.)
        SearchControls controls = StringUtils.isBlank(splitByAttribute) ? subtree(NO_ATTRIBUTES) : subtree(splitByAttribute);

        List<String> results = pagedSearch(baseDn, filter, controls, (ContextMapper<String>) ctx -> {
            Attributes attrs = attributesOf(ctx);
            if (!StringUtils.isBlank(splitByAttribute) && attrs != null && attrs.get(splitByAttribute) != null) {
                return attrs.get(splitByAttribute).get().toString();
            }else{
                return "EMPTY";
            }
        });

        Map<String, Integer> counts = new HashMap<>();
        for (String result : results) {
            counts.put(result, counts.getOrDefault(result, 0) + 1);
        }
        return counts;
    }

    public Optional<LdapGroup> lookupGroup(LdapDn dn) {
        LdapGroup group = null;
        try {
            group = ldapTemplate.lookup(dn.toString(), GROUP_ATTRIBUTES, new LdapGroupContextMapper());
        } catch (Exception e) {
            log.error("Not found: %s".formatted(dn), e);
        }

        return Optional.ofNullable(group);
    }

    public List<LdapGroup> getGroups(TopLevelGroupProperties topProps) {
        try {
            return pagedSearch(
                    topProps.getGroupsBaseDn(),
                    "(objectClass=groupOfUniqueNames)",
                    subtree(GROUP_ATTRIBUTES),
                    new LdapGroupContextMapper()
            );
        } catch (NameNotFoundException e) {
            // Configured groups base DN doesn't exist; treat as no groups.
            log.warn("Groups base DN not found, returning none: {}", topProps.getGroupsBaseDn());
            return new ArrayList<>();
        }
    }

    public List<String> getAllUniqueValuesForAttributes(String attribute, Filter additionalFilter) {
        long start = System.currentTimeMillis();

        AndFilter filter = new AndFilter();
        filter.and(new PresentFilter("objectClass"));
        filter.and(new PresentFilter(attribute)); // Match any value for the attribute
        if (additionalFilter != null) {
            filter.and(additionalFilter);
        }

        List<String> allValues = pagedSearch(
                properties.getUserBaseDn(),
                filter.encode(),
                subtree(attribute),
                (ContextMapper<String>) ctx -> {
                    Attributes attrs = attributesOf(ctx);
                    if (attrs != null && attrs.get(attribute) != null) {
                        return attrs.get(attribute).get().toString();
                    } else {
                        return null;
                    }
                }
        );

        List<String> unique = new ArrayList<>(new HashSet<>(allValues));

        // A full-subtree scan of every user entry: the slowest query the app makes, and
        // the input to the whole org chart.
        log.debug("Read attribute {} from {} entries, {} distinct value(s), in {}ms",
                attribute, allValues.size(), unique.size(), System.currentTimeMillis() - start);

        return unique;
    }

    public List<String> getGroupMembers(LdapDn dn) {
        List<LdapDn> members = new ArrayList<>();

        try {
            members = readMembers(dn.toString());
        } catch (Exception e) {
            log.error("Failed to get members for group: {}", dn.toString(), e);
        }

        // An empty membership is a legitimate result and also the usual cause of an
        // empty user table, so make the two distinguishable from the log.
        log.debug("Group {} has {} member(s)", dn, members.size());

        return members.stream()
                .map(LdapDn::toString)
                .toList();
    }

    /** A group's uniqueMember values; throws when the group does not exist. */
    private List<LdapDn> readMembers(String dn) {
        return ldapTemplate.lookup(dn, MEMBER_ATTRIBUTES, (AttributesMapper<List<LdapDn>>) attrs -> {
                List<LdapDn> members1 = new ArrayList<>();
                attrs.getIDs().asIterator().forEachRemaining(a -> {
                    if (a.equalsIgnoreCase("uniqueMember")) {
                        Attribute attribute = attrs.get(a);
                        try {
                            NamingEnumeration<?> enumeration = attribute.getAll();
                            try {
                                enumeration.asIterator().forEachRemaining(m -> members1.add(new LdapDn(m.toString())));
                            } finally {
                                enumeration.close();
                            }
                        } catch (NamingException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                return members1;
            });
    }


    private LdapGroup buildGroupHierarchyFromAttribute(String dn, List<String> visited) {
        if (visited.contains(dn)) {
            // Prevent infinite loops from cyclic references
            return null;
        }
        visited.add(dn);

        List<LdapGroup> results = ldapTemplate.search(
                LdapQueryBuilder.query()
                        .base("")
                        .attributes(GROUP_ATTRIBUTES)
                        .where("distinguishedName").is(dn),
                new LdapGroupContextMapper()
        );

        if (results.isEmpty()) return null;

        LdapGroup group = results.getFirst();

        // Process 'seeAlso' attributes for nested groups
        try {
            Attribute seeAlsoAttr = ldapTemplate.lookup(dn, new String[]{"seeAlso"}, (AttributesMapper<Attribute>)
                    attributes -> attributes.get("seeAlso"));
            if (seeAlsoAttr != null) {
                NamingEnumeration<?> enumeration = seeAlsoAttr.getAll();
                try {
                    while (enumeration.hasMore()) {
                        String childDn = (String) enumeration.next();
                        LdapGroup childGroup = buildGroupHierarchyFromAttribute(childDn, visited);
                        if (childGroup != null) {
                            group.addChild(childGroup);
                        }
                    }
                } finally {
                    enumeration.close();
                }
            }
        } catch (Exception e) {
            // No seeAlso or lookup failure is fine; just skip
        }

        return group;
    }



    /**
     * Builds the full group tree under a DN by walking the directory.
     *
     * <p>Not cached here, for two reasons. LdapGroupLoader's cache already holds the result, and a
     * second cache in front of this method meant that cache's refresh got the stale copy back until
     * this one expired. It also recurses into itself, and a self-call does not pass through the
     * Spring proxy, so {@code @Cacheable} here only ever stored the DN the walk started from - every
     * group underneath was rebuilt from scratch the first time anyone opened it.
     *
     * <p>Caching is {@link CacheService}'s job: it holds the one cache and warms every descendant
     * this walk produces.
     *
     * @throws GroupTreeIncompleteException if the directory fails during the walk; the tree built so
     *         far travels on the exception so it can be rendered without being cached.
     */
    public LdapGroup buildGroupRecursiveInternal(String dn) {

        // Reading the members also tells us whether the entry exists: one lookup, one attribute.
        List<LdapDn> groupMembers;
        try {
            groupMembers = readMembers(dn);
        } catch (Exception e) {
            return null;
        }

        LdapDn ldapDn = new LdapDn(dn);

        LdapGroup node = new LdapGroup(dn, ldapDn.getName());

        node.setGroupSize(groupMembers.size());

        // 🔍 Find immediate child OUs of this DN
        List<Name> childDns;
        try {
            childDns = ldapTemplate.search(
                    LdapQueryBuilder.query()
                            .base(dn)
                            .searchScope(SearchScope.ONELEVEL)
                            .attributes(NO_ATTRIBUTES)
                            .where("objectClass").is("groupOfUniqueNames"),
                    (ContextMapper<Name>) ctxObj -> {
                        DirContextAdapter context = (DirContextAdapter) ctxObj;
                        return context.getDn();
                    }
            );
        } catch (Exception e) {
            // Returning the childless node here would be cached as a complete tree, and a
            // one-off referral or timeout would then serve a group with no children until
            // the entry expired. Carry the node out on the exception instead: still
            // renderable, never cached.
            log.warn("Failed to enumerate child groups under {}: {}", dn, e.getMessage());
            throw new GroupTreeIncompleteException(dn, node, e);
        }

        boolean incomplete = false;
        Throwable firstFailure = null;

        for (Name childDn : childDns) {
            try {
                LdapGroup childNode = buildGroupRecursiveInternal(childDn.toString());
                if (childNode != null) {
                    node.addChild(childNode);
                }
            } catch (GroupTreeIncompleteException e) {
                // Keep whatever that branch managed to build and carry on with its
                // siblings, as this walk always has - but remember the tree is short of
                // something, so no part of it gets cached as complete.
                if (e.getPartial() != null) {
                    node.addChild(e.getPartial());
                }
                incomplete = true;
                if (firstFailure == null) {
                    firstFailure = e.getCause();
                }
            }
        }

        if (incomplete) {
            throw new GroupTreeIncompleteException(dn, node, firstFailure);
        }

        return node;
    }

    /*
    private String getOuNameFromDn(String dn) {
        // Example: "ou=Engineering,ou=People,dc=example,dc=com" -> "Engineering"
        if (dn == null) return "";
        String[] parts = dn.split(",");
        for (String part : parts) {
            if (part.trim().toLowerCase().startsWith("cn=") || part.trim().toLowerCase().startsWith("ou=")) {
                return part.substring(3);
            }
        }
        return dn;
    }

     */

    /**
     * Finds all groupOfUniqueNames where the given userDN is a uniqueMember.
     *
     * @param userDn full DN of the user, e.g. "uid=john,ou=Users,dc=example,dc=com"
     * @return list of group CNs (or full DNs, depending on mapping)
     */
    public List<LdapGroup> findGroupsForUser(String userDn) {
        if (groupsForUserCache == null || userDn == null) {
            return searchGroupsForUser(userDn);
        }

        // Permission checks ask on every request; membership changes show up within the cache period.
        String key = userDn.toLowerCase();

        List<LdapGroup> cached = groupsForUserCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // Searched outside the cache rather than through get(key, mappingFunction). That runs under
        // computeIfAbsent, which holds the bin for the key: while one slow search is in flight every
        // other request for the same user blocks behind it, so reloading the page joins the stuck
        // lookup instead of starting a new one, and the page stays dead until the first one returns.
        // The cost of computing outside is that concurrent first-time callers may each search; a
        // duplicated lookup is cheaper than a wedged request.
        List<LdapGroup> groups = searchGroupsForUser(userDn);

        groupsForUserCache.put(key, groups);

        return groups;
    }

    private List<LdapGroup> searchGroupsForUser(String userDn) {
        // Build LDAP filter: (&(objectClass=groupOfUniqueNames)(uniqueMember=<userDn>))

        List<LdapGroup> groups = new ArrayList<>();

        for (TopLevelGroupProperties topProp : properties.getGroups()) {
            try {
                groups.addAll(findGroupsForUserWithBaseDn(new LdapDn(topProp.getGroupsBaseDn()),
                        new LdapDn(userDn)));
            } catch (NameNotFoundException e) {
                // A configured groups base DN doesn't exist in the directory; skip it rather
                // than failing the whole lookup (e.g. an optional/unprovisioned OU).
                log.warn("Groups base DN not found, skipping: {}", topProp.getGroupsBaseDn());
            } catch (Exception e) {
                log.error("Failed to search groups under {}", topProp.getGroupsBaseDn(), e);
            }
        }

        // Drives both the "Member Of" list and the permission checks.
        log.debug("User {} is a member of {} group(s)", userDn, groups.size());

        return List.copyOf(groups);
    }

    private List<LdapGroup> findGroupsForUserWithBaseDn(LdapDn groupDn, LdapDn userDn) {
        AndFilter filter = new AndFilter();
        filter.and(new EqualsFilter("objectClass", "groupOfUniqueNames"));
        filter.and(new EqualsFilter("uniqueMember", userDn.toString()));

        return pagedSearch(
                groupDn.toString(),  // base DN (empty means use the default search base)
                filter.encode(),
                subtree(GROUP_ATTRIBUTES),
                new LdapGroupContextMapper()
        );
    }

    private static final class LdapUserContextMapper implements ContextMapper<LdapUser> {

        private final ApplicationProperties appProperties;

        LdapUserContextMapper(ApplicationProperties properties) {
            this.appProperties = properties;
        }

        @Override
        public LdapUser mapFromContext(Object o) throws NamingException {
            LdapUser.LdapUserBuilder builder = LdapUser.builder();

            Attributes attributes;
            String dn;

            if(o instanceof DirContextAdapter c) {
                attributes = c.getAttributes();
                dn = c.getNameInNamespace();
            } else if (o instanceof DirContext c) {
                attributes = c.getAttributes("");
                dn = c.getNameInNamespace();
            }else{
                throw new IllegalArgumentException("Unsupported: "+o.getClass());
            }

            builder.dn(LdapDn.normalize(dn));

            if (attributes != null) {

                NamingEnumeration<?> allAttributes = attributes.getAll();
                try {
                    allAttributes.asIterator().forEachRemaining(obj -> {
                        Attribute attr = (Attribute) obj;
                        if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapOrganizationAttribute())) {
                            try {
                                String org = attr.get().toString();
                                builder.organization(org);
                            } catch (NamingException e) {
                                log.error("Could not map org attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapEmployeeTypeAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.employeeType(type);
                            } catch (NamingException e) {
                                log.error("Could not map empType attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapLocationAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.location(type);
                            } catch (NamingException e) {
                                log.error("Could not map location attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapBranchAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.branch(type);
                            } catch (NamingException e) {
                                log.error("Could not map branch attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapManagerIdAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.managerId(type);
                            } catch (NamingException e) {
                                log.error("Could not map branch attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getManagerLdapIdAttribute())) {
                            try {
                                builder.managerLdapId(attr.get().toString());
                            } catch (NamingException e) {
                                log.error("Could not map managerLdapId attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapEmailAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.email(type);
                            } catch (NamingException e) {
                                log.error("Could not map branch attribute: ", e);
                            }
                        } else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapPhoneAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.phoneNumber(type);
                            } catch (NamingException e) {
                                log.error("Could not map branch attribute: ", e);
                            }
                        }else if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapDutySubOrganizationAttribute())) {
                            try {
                                String type = attr.get().toString();
                                builder.dutySubOrganization(type);
                            } catch (NamingException e) {
                                log.error("Could not map dutySubOrganization attribute: ", e);
                            }
                        }

                    });
                } finally {
                    allAttributes.close();
                }

            }

            return builder.build();
        }
    }

    private static final class LdapGroupContextMapper implements ContextMapper<LdapGroup> {

        @Override
        public LdapGroup mapFromContext(Object o) throws NamingException {
            Attributes attrs;
            String dn;

            if(o instanceof DirContextAdapter c) {
                attrs = c.getAttributes();
                dn = c.getNameInNamespace();
            } else if (o instanceof DirContext c) {
                attrs = c.getAttributes("");
                dn = c.getNameInNamespace();
            }else{
                throw new IllegalArgumentException("Unsupported: "+o.getClass());
            }

            dn = LdapDn.normalize(dn);

            LdapGroup group = new LdapGroup();
            group.setDn(dn);
            if (attrs.get("distinguishedName") != null)
                group.setDn((String) attrs.get("distinguishedName").get());
            if (attrs.get("cn") != null)
                group.setCn((String) attrs.get("cn").get());
            if (attrs.get("description") != null)
                group.setDescription((String) attrs.get("description").get());
            if (attrs.get("owner") != null)
                group.setManager((String) attrs.get("owner").get());
            return group;
        }
    }

    /**
     * Escapes LDAP filter special characters to prevent LDAP injection attacks.
     * Characters: * ( ) \ NUL
     */
    public static String escapeLdapFilter(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\5c")
                .replace("*", "\\2a")
                .replace("(", "\\28")
                .replace(")", "\\29")
                .replace("\0", "\\00");
    }
}
