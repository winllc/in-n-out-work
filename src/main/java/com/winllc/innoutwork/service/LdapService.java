package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.ldap.control.PagedResultsCookie;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.*;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.ldap.query.SearchScope;
import org.springframework.stereotype.Service;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class LdapService {

    private static final Logger log = LoggerFactory.getLogger(LdapService.class);

    private final Cache<String, LdapGroup> groupCache;

    private final LdapTemplate ldapTemplate;
    private final ApplicationProperties properties;


    public LdapService(LdapTemplate ldapTemplate, ApplicationProperties properties) {
        this.ldapTemplate = ldapTemplate;

        groupCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(properties.getCacheDurationMinutes()))  // adjust as needed
                .maximumSize(5000) // prevent unbounded memory use
                .build();
        this.properties = properties;
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

    public LdapGroup getGroupHierarchy(String dn) {
        return buildGroupRecursiveInternal(dn);
    }

    // Alternative: More efficient approach that doesn't iterate through all previous pages
    public List<UserStatus> search(String baseDn, String filter, int pageNumber, int pageSize) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);

        return ldapTemplate.search(
                baseDn,
                filter,
                controls,
                (ContextMapper<UserStatus>) ctx -> {
                    DirContextAdapter context = (DirContextAdapter) ctx;
                    UserStatus user = UserStatus.builder()
                            .dn(context.getDn().toString())
                            .build();
                    return user;
                }
        );

    }

    private long count(String baseDn, String filter) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[0]); // don’t fetch attributes, just DNs

        List<?> results = ldapTemplate.search(baseDn, filter, controls, (Object ctx) -> null);
        return results.size();
    }


    public List<LdapGroup> getGroups(){
        List<LdapGroup> groups = ldapTemplate.search(
                properties.getBaseDn(),
                "(objectClass=groupOfUniqueNames)",
                (AttributesMapper<LdapGroup>) attrs -> mapGroup(attrs)
        );
        return groups;
    }

    public List<String> getGroupMembers(String groupName) {
        List<String> members = new ArrayList<>();

        try {
            LdapQuery query = LdapQueryBuilder.query()
                    .base(properties.getBaseDn())
                    .attributes("uniqueMember")
                    .searchScope(SearchScope.SUBTREE)
                    .countLimit(1)
                    .filter("cn=" + groupName);

            List<Attribute> uniqueMember = ldapTemplate.search(query, (AttributesMapper<Attribute>)
                    attributes -> attributes.get("uniqueMember"));

            if (uniqueMember != null) {
                NamingEnumeration<?> enumeration = uniqueMember.get(0).getAll();
                while (enumeration.hasMore()) {
                    String memberDn = (String) enumeration.next();
                    members.add(memberDn);
                }
            }
        } catch (Exception e) {
            log.error("Failed to get members for group: {}", groupName, e);
        }

        return members;
    }

    private LdapGroup buildGroupHierarchyFromAttribute(String dn, List<String> visited) {
        if (visited.contains(dn)) {
            // Prevent infinite loops from cyclic references
            return null;
        }
        visited.add(dn);

        List<LdapGroup> results = ldapTemplate.search(
                "",
                "(distinguishedName=" + dn + ")",
                (AttributesMapper<LdapGroup>) attrs -> mapGroup(attrs)
        );

        if (results.isEmpty()) return null;

        LdapGroup group = results.getFirst();

        // Process 'seeAlso' attributes for nested groups
        try {
            Attribute seeAlsoAttr = ldapTemplate.lookup(dn, (AttributesMapper<Attribute>)
                    attributes -> attributes.get("seeAlso"));
            if (seeAlsoAttr != null) {
                NamingEnumeration<?> enumeration = seeAlsoAttr.getAll();
                while (enumeration.hasMore()) {
                    String childDn = (String) enumeration.next();
                    LdapGroup childGroup = buildGroupHierarchyFromAttribute(childDn, visited);
                    if (childGroup != null) {
                        group.addChild(childGroup);
                    }
                }
            }
        } catch (Exception e) {
            // No seeAlso or lookup failure is fine; just skip
        }

        return group;
    }

    private LdapGroup mapGroup(Attributes attrs) throws NamingException {
        LdapGroup group = new LdapGroup();
        if (attrs.get("distinguishedName") != null)
            group.setDn((String) attrs.get("distinguishedName").get());
        if (attrs.get("cn") != null)
            group.setCn((String) attrs.get("cn").get());
        if (attrs.get("description") != null)
            group.setDescription((String) attrs.get("description").get());
        return group;
    }

    private LdapGroup buildGroupRecursive(String dn) {
        // Lookup the current OU entry (might also be the base context)
        DirContextOperations ctx;
        try {
            ctx = ldapTemplate.lookupContext(dn);
        } catch (Exception e) {
            return null;
        }

        String ouName = getOuNameFromDn(dn);
        LdapGroup node = new LdapGroup(dn, ouName);

        // Find child OUs directly under this DN

        List<Name> childDns = ldapTemplate.search(
                LdapQueryBuilder.query()
                        .base(dn)
                        .searchScope(SearchScope.ONELEVEL)
                        .where("objectClass").is("groupOfUniqueNames"),
                new ContextMapper<Name>() {
                    @Override
                    public Name mapFromContext(Object ctx) throws NamingException {
                        DirContextAdapter context = (DirContextAdapter) ctx;
                        return context.getDn();
                    }
                }
        );

        for (Name childDn : childDns) {
            LdapGroup childNode = buildGroupRecursive(childDn.toString());
            if (childNode != null) {
                node.addChild(childNode);
            }
        }

        return node;
    }

    private LdapGroup buildGroupRecursiveInternal(String dn) {

        // ✅ Check cache first
        LdapGroup cached = groupCache.getIfPresent(dn);
        if (cached != null) {
            return cached;
        }

        // Lookup LDAP entry for this DN
        DirContextOperations ctx;
        try {
            ctx = ldapTemplate.lookupContext(dn);
        } catch (Exception e) {
            return null;
        }

        String ouName = getOuNameFromDn(dn);
        LdapGroup node = new LdapGroup(dn, ouName);

        // Put in cache immediately to avoid recursion loops
        groupCache.put(dn, node);

        // 🔍 Find immediate child OUs of this DN
        List<Name> childDns = ldapTemplate.search(
                LdapQueryBuilder.query()
                        .base(dn)
                        .searchScope(SearchScope.ONELEVEL)
                        .where("objectClass").is("groupOfUniqueNames"),
                (ContextMapper<Name>) ctxObj -> {
                    DirContextAdapter context = (DirContextAdapter) ctxObj;
                    return context.getDn();
                }
        );

        for (Name childDn : childDns) {
            LdapGroup childNode = buildGroupRecursiveInternal(childDn.toString());
            if (childNode != null) {
                node.addChild(childNode);
            }
        }

        return node;
    }

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

    /**
     * Finds all groupOfUniqueNames where the given userDN is a uniqueMember.
     *
     * @param userDn full DN of the user, e.g. "uid=john,ou=Users,dc=example,dc=com"
     * @return list of group CNs (or full DNs, depending on mapping)
     */
    public List<String> findGroupsForUser(String userDn) {
        // Build LDAP filter: (&(objectClass=groupOfUniqueNames)(uniqueMember=<userDn>))
        AndFilter filter = new AndFilter();
        filter.and(new EqualsFilter("objectClass", "groupOfUniqueNames"));
        filter.and(new EqualsFilter("uniqueMember", userDn));

        return ldapTemplate.search(
                properties.getBaseDn(),  // base DN (empty means use the default search base)
                filter.encode(),
                (AttributesMapper<String>) attrs -> {
                    Attribute cn = attrs.get("cn");
                    return cn != null ? (String) cn.get() : null;
                }
        );
    }

}
