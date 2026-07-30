package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapDn;
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
import javax.naming.directory.DirContext;
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
    public List<UserStatus> search(String filter, int pageNumber, int pageSize) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);

        return ldapTemplate.search(
                properties.getUserBaseDn(),
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

    public List<LdapUser> searchUsers(LdapQuery query) {

        return ldapTemplate.search(
                query,
                new LdapUserContextMapper(properties)
        );
    }

    public Optional<LdapUser> lookupUser(LdapDn dn) {
        LdapUser user = null;
        try {

            if (properties.isLookupOnDnAttribute()) {
                LdapQuery query = LdapQueryBuilder.query()
                        .base(properties.getUserBaseDn())
                        .countLimit(1)
                        .filter(new EqualsFilter(properties.getUserDnAttribute(), dn.toString()));

                List<LdapUser> users = ldapTemplate.search(query, new LdapUserContextMapper(properties));

                if (!CollectionUtils.isEmpty(users)) {
                    user = users.getFirst();
                }

            } else {
                user = ldapTemplate.lookup(dn.toString(), new LdapUserContextMapper(properties));
            }

        } catch (Exception e) {
            log.error("Not found: %s".formatted(dn), e);
        }

        return Optional.ofNullable(user);
    }

    public Optional<LdapUser> lookupUser(String attribute, String value) {
        LdapQuery query = LdapQueryBuilder.query()
                .base(properties.getUserBaseDn())
                .countLimit(1)
                .filter(new EqualsFilter(attribute, value));

        List<LdapUser> users = ldapTemplate.search(query, new LdapUserContextMapper(properties));
        if (!CollectionUtils.isEmpty(users)) {
            return Optional.of(users.getFirst());
        } else {
            return Optional.empty();
        }
    }

    public long count(String baseDn, String filter) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[0]); // don’t fetch attributes, just DNs

        List<?> results = ldapTemplate.search(baseDn, filter, controls, (Object ctx) -> null);
        return results.size();
    }

    public Optional<LdapGroup> lookupGroup(LdapDn dn) {
        LdapGroup group = null;
        try {
            group = ldapTemplate.lookup(dn.toString(), new LdaGroupContextMapper());
        } catch (Exception e) {
            log.error("Not found: %s".formatted(dn), e);
        }

        return Optional.ofNullable(group);
    }

    public List<LdapGroup> getGroups(TopLevelGroupProperties topProps) {
        return ldapTemplate.search(
                topProps.getGroupsBaseDn(),
                "(objectClass=groupOfUniqueNames)",
                new LdaGroupContextMapper()
        );
    }

    public List<String> getGroupMembers(LdapDn dn) {
        List<LdapDn> members = new ArrayList<>();

        try {
            members = ldapTemplate.lookup(dn.toString(), (AttributesMapper<List<LdapDn>>) attrs -> {
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

        } catch (Exception e) {
            log.error("Failed to get members for group: {}", dn.toString(), e);
        }

        return members.stream()
                .map(LdapDn::toString)
                .toList();
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
                        .where("distinguishedName").is(dn),
                new LdaGroupContextMapper()
        );

        if (results.isEmpty()) return null;

        LdapGroup group = results.getFirst();

        // Process 'seeAlso' attributes for nested groups
        try {
            Attribute seeAlsoAttr = ldapTemplate.lookup(dn, (AttributesMapper<Attribute>)
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



    @Cacheable(cacheNames = "ldapGroups", key = "#dn", unless = "#result == null")
    public LdapGroup buildGroupRecursiveInternal(String dn) {

        // Lookup LDAP entry for this DN
        try {
            ldapTemplate.lookupContext(dn);
        } catch (Exception e) {
            return null;
        }

        LdapDn ldapDn = new LdapDn(dn);

        LdapGroup node = new LdapGroup(dn, ldapDn.getName());

        List<String> groupMembers = getGroupMembers(ldapDn);
        node.setGroupSize(groupMembers.size());

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
        // Build LDAP filter: (&(objectClass=groupOfUniqueNames)(uniqueMember=<userDn>))

        List<LdapGroup> groups = new ArrayList<>();

        for (TopLevelGroupProperties topProp : properties.getGroups()) {
            List<LdapGroup> temp = findGroupsForUserWithBaseDn(new LdapDn(topProp.getGroupsBaseDn()),
                    new LdapDn(userDn));
            groups.addAll(temp);
        }

        return groups;
    }

    private List<LdapGroup> findGroupsForUserWithBaseDn(LdapDn groupDn, LdapDn userDn) {
        AndFilter filter = new AndFilter();
        filter.and(new EqualsFilter("objectClass", "groupOfUniqueNames"));
        filter.and(new EqualsFilter("uniqueMember", userDn.toString()));

        return ldapTemplate.search(
                groupDn.toString(),  // base DN (empty means use the default search base)
                filter.encode(),
                new LdaGroupContextMapper()
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

            builder.dn(dn.replace(", ", ","));

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
                        }

                    });
                } finally {
                    allAttributes.close();
                }

            }

            return builder.build();
        }
    }

    private static final class LdaGroupContextMapper implements ContextMapper<LdapGroup> {

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

            dn = dn.replace(", ", ",");

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
}
