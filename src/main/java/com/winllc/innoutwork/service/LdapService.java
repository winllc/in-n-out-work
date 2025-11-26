package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.ldap.core.*;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
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
import javax.naming.directory.SearchControls;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class LdapService {

    private static final Logger log = LoggerFactory.getLogger(LdapService.class);

    private final LdapTemplate ldapTemplate;
    private final ApplicationProperties properties;


    public LdapService(LdapTemplate ldapTemplate,
                       ApplicationProperties properties) {
        this.ldapTemplate = ldapTemplate;
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

    public Optional<LdapUser> lookupUser(String dn) {
        LdapUser user = null;
        try {

            if (properties.isLookupOnDnAttribute()) {
                LdapQuery query = LdapQueryBuilder.query()
                        .base(properties.getBaseDn())
                        .countLimit(1)
                        .filter(new EqualsFilter(properties.getUserDnAttribute(), dn));

                List<LdapUser> users = ldapTemplate.search(query, new LdapUserContextMapper(properties));

                if (!CollectionUtils.isEmpty(users)) {
                    user = users.getFirst();
                }

            } else {
                user = ldapTemplate.lookup(dn, new LdapUserContextMapper(properties));
            }

        } catch (Exception e) {
            log.error("Not found: %s".formatted(dn), e);
        }

        return Optional.ofNullable(user);
    }

    public long count(String baseDn, String filter) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[0]); // don’t fetch attributes, just DNs

        List<?> results = ldapTemplate.search(baseDn, filter, controls, (Object ctx) -> null);
        return results.size();
    }


    public List<LdapGroup> getGroups(){
        return ldapTemplate.search(
                properties.getBaseDn(),
                "(objectClass=groupOfUniqueNames)",
                (ContextMapper<LdapGroup>) ctx -> {
                    DirContextAdapter context = (DirContextAdapter) ctx;

                    return mapGroup(context.getDn().toString(), context.getAttributes());
                }
        );
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
                (ContextMapper<LdapGroup>) ctx -> {
                    DirContextAdapter context = (DirContextAdapter) ctx;

                    return mapGroup(context.getDn().toString(), context.getAttributes());
                }
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

    private LdapGroup mapGroup(String dn, Attributes attrs) throws NamingException {
        LdapGroup group = new LdapGroup();
        group.setDn(dn);
        if (attrs.get("distinguishedName") != null)
            group.setDn((String) attrs.get("distinguishedName").get());
        if (attrs.get("cn") != null)
            group.setCn((String) attrs.get("cn").get());
        if (attrs.get("description") != null)
            group.setDescription((String) attrs.get("description").get());
        return group;
    }


    @Cacheable(cacheNames = "ldapGroups", key = "#dn")
    public LdapGroup buildGroupRecursiveInternal(String dn) {


        // Lookup LDAP entry for this DN
        try {
            ldapTemplate.lookupContext(dn);
        } catch (Exception e) {
            return null;
        }

        String ouName = getOuNameFromDn(dn);
        LdapGroup node = new LdapGroup(dn, ouName);

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
    public List<LdapGroup> findGroupsForUser(String userDn) {
        // Build LDAP filter: (&(objectClass=groupOfUniqueNames)(uniqueMember=<userDn>))
        AndFilter filter = new AndFilter();
        filter.and(new EqualsFilter("objectClass", "groupOfUniqueNames"));
        filter.and(new EqualsFilter("uniqueMember", userDn));

        return ldapTemplate.search(
                properties.getBaseDn(),  // base DN (empty means use the default search base)
                filter.encode(),
                (ContextMapper<LdapGroup>) ctx -> {
                    DirContextAdapter context = (DirContextAdapter) ctx;

                    return mapGroup(context.getDn().toString(), context.getAttributes());
                }
        );
    }

    private static final class LdapUserContextMapper  implements ContextMapper<LdapUser> {

        private final ApplicationProperties appProperties;

        LdapUserContextMapper(ApplicationProperties properties) {
            this.appProperties = properties;
        }

        @Override
        public LdapUser mapFromContext(Object o) throws NamingException {
            LdapUser.LdapUserBuilder builder = LdapUser.builder();
            DirContextAdapter context = (DirContextAdapter) o;

            builder.dn(context.getNameInNamespace().replaceAll(", ", ","));

            if(context.getAttributes() != null) {

                context.getAttributes().getAll().asIterator().forEachRemaining(attr -> {
                    if (attr.getID().equalsIgnoreCase(appProperties.getUserLdapOrganizationAttribute())) {
                        try {
                            String org = attr.get().toString();
                            builder.organization(org);
                        } catch (NamingException e) {
                            log.error("Could not map org attribute: ", e);
                        }
                    }else if(attr.getID().equalsIgnoreCase(appProperties.getUserLdapEmployeeTypeAttribute())){
                        try {
                            String type = attr.get().toString();
                            builder.employeeType(type);
                        } catch (NamingException e) {
                            log.error("Could not map org attribute: ", e);
                        }
                    }
                });

            }

            return builder.build();
        }
    }
}
