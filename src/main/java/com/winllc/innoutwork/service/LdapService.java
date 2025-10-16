package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.control.PagedResultsCookie;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.ldap.query.SearchScope;
import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import java.util.ArrayList;
import java.util.List;

@Service
public class LdapService {

    private static final Logger log = LoggerFactory.getLogger(LdapService.class);

    private final LdapTemplate ldapTemplate;


    public LdapService(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }

    /**
     * Recursively translates an LDAP group and all nested groups (via 'seeAlso') into Java objects.
     *
     * @param dn distinguished name (DN) of the root group
     * @return hierarchical LdapGroup object
     */
    public LdapGroup getGroupHierarchy(String dn) {
        return buildGroupHierarchy(dn, new ArrayList<>());
    }

    public List<LdapUser> search(String baseDn, String filter, int pageNumber, int pageSize) {
        List<LdapUser> results = new ArrayList<>();

        PagedResultsDirContextProcessor pageProcessor;

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);

        PagedResultsCookie pagedCookie = null;
        int currentPage = 1;

        do {
            pageProcessor = new PagedResultsDirContextProcessor(pageSize, pagedCookie);

            List<LdapUser> page = ldapTemplate.search(
                    baseDn,
                    filter,
                    controls,
                    (AttributesMapper<LdapUser>) attrs -> {
                        LdapUser user = new LdapUser();
                        user.setCn((String) attrs.get("cn").get());
                        user.setSn((String) attrs.get("sn").get());
                        //user.setEmail(attrs.get("mail") != null ? (String) attrs.get("mail").get() : null);
                        return user;
                    },
                    pageProcessor
            );

            if (currentPage == pageNumber) {
                results.addAll(page);
                break;
            }

            pagedCookie = pageProcessor.getCookie();
            currentPage++;

        } while (pagedCookie != null && pagedCookie.getCookie() != null &&
                pagedCookie.getCookie().length > 0);

        return results;
    }

    public List<LdapGroup> getGroups(){
        List<LdapGroup> groups = ldapTemplate.search(
                "dc=winllc,dc=com",
                "(objectClass=groupOfUniqueNames)",
                (AttributesMapper<LdapGroup>) attrs -> mapGroup(attrs)
        );
        return groups;
    }

    public List<String> getGroupMembers(String groupName) {
        List<String> members = new ArrayList<>();

        try {
            LdapQuery query = LdapQueryBuilder.query()
                    .base("dc=winllc,dc=com")
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

    private LdapGroup buildGroupHierarchy(String dn, List<String> visited) {
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
                    LdapGroup childGroup = buildGroupHierarchy(childDn, visited);
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
}
