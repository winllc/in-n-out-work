package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How much the directory is asked for, against a real server whose size limit is set below the fixture's
 * population, as a production directory's is below its user count.
 */
class LdapServiceEfficiencyTest {

    private static final String BASE = InMemoryDirectory.BASE_DN;
    private static final String BOB = "cn=Bob Barker,ou=Users," + BASE;
    private static final String ENGINEERING = "cn=Engineering,ou=Groups," + BASE;
    /** Five users; the server returns at most two entries per search. */
    private static final int SIZE_LIMIT = 2;

    private static InMemoryDirectory directory;

    private ApplicationProperties props;
    private LdapService ldapService;

    @BeforeAll
    static void startDirectory() {
        directory = InMemoryDirectory.start(SIZE_LIMIT);
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    @BeforeEach
    void setUp() {
        props = new ApplicationProperties();
        props.setUserBaseDn(BASE);
        props.setUserLdapFilter("(objectclass=inetOrgPerson)");
        props.setUserLdapEmployeeTypeAttribute("employeeType");
        props.setUserLdapOrganizationAttribute("o");
        props.setUserLdapLocationAttribute("l");
        props.setUserLdapManagerIdAttribute("title");
        props.setManagerLdapIdAttribute("street");
        props.setUserLdapDutySubOrganizationAttribute("departmentNumber");
        props.getLdap().setPageSize(SIZE_LIMIT);
        TopLevelGroupProperties groups = new TopLevelGroupProperties();
        groups.setGroupsBaseDn("ou=Groups," + BASE);
        props.setGroups(List.of(groups));

        ldapService = new LdapService(directory.ldapTemplate(), props);
        directory.clearSearchRequests();
    }

    private static String requestedAttributes(String accessLogLine) {
        int at = accessLogLine.indexOf("attrs=\"");
        return at < 0 ? "" : accessLogLine.substring(at + 7, accessLogLine.indexOf('"', at + 7));
    }

    // --- paging -------------------------------------------------------------------------------------------

    @Test
    void searchesPastTheServersSizeLimitReturnEveryEntry() {
        assertEquals(5, ldapService.search("(objectClass=inetOrgPerson)").size());
        assertEquals(5, ldapService.findAllUsers().size());
        assertEquals(5, ldapService.count(BASE, "(objectClass=inetOrgPerson)"));
        assertEquals(3, ldapService.getGroups(props.getGroups().getFirst()).size());
        assertEquals(3, ldapService.getAllUniqueValuesForAttributes("departmentNumber", null).size());
    }

    /**
     * The loop is bounded. A directory that keeps returning a cookie - a referral, a proxy that takes
     * the paged results control without honouring it - would otherwise page for ever and hold the
     * request open with it, which is what a permanently spinning page looks like.
     */
    @Test
    void pagingStopsAtTheConfiguredPageLimit() {
        props.getLdap().setMaxPages(1);

        // Five users, two per page: with one page allowed only the first page comes back, and the
        // search returns rather than running on.
        assertEquals(SIZE_LIMIT, ldapService.search("(objectClass=inetOrgPerson)").size());
    }

    @Test
    void theDefaultPageLimitIsWellClearOfRealResultSets() {
        assertEquals(1000, new ApplicationProperties().getLdap().getMaxPages());
        assertEquals(5, ldapService.search("(objectClass=inetOrgPerson)").size());
    }

    /** What paging prevents: the template ignores the size-limit error, so the rest just go missing. */
    @Test
    void withoutPagingTheSizeLimitSilentlyCutsResultsShort() {
        props.getLdap().setPageSize(0);

        assertEquals(SIZE_LIMIT, ldapService.search("(objectClass=inetOrgPerson)").size());
    }

    // --- returned attributes --------------------------------------------------------------------------------

    /** Groups are read for their name, description and owner; never their (possibly huge) member list. */
    @Test
    void groupSearchesDoNotFetchMemberLists() {
        ldapService.findGroupsForUser(BOB);
        ldapService.getGroups(props.getGroups().getFirst());
        LdapGroup engineering = ldapService.lookupGroup(new LdapDn(ENGINEERING)).orElseThrow();

        assertEquals("Engineering", engineering.getCn());
        assertEquals("cn=Alice Adams,ou=Users," + BASE, engineering.getManager());
        List<String> requests = directory.searchRequests();
        assertFalse(requests.isEmpty());
        for (String request : requests) {
            String attrs = requestedAttributes(request);
            assertFalse(attrs.isEmpty(), "all attributes requested: " + request);
            assertFalse(attrs.toLowerCase().contains("uniquemember"), "member list requested: " + request);
        }
    }

    @Test
    void userSearchesAskOnlyForTheConfiguredAttributes() {
        LdapUser bob = ldapService.lookupUser(new LdapDn(BOB)).orElseThrow();
        ldapService.findUsersReportingTo("MGR-100");
        ldapService.search("(cn=Bob Barker)");

        assertEquals("PT", bob.getEmployeeType());
        List<String> requests = directory.searchRequests();
        assertEquals(3, requests.size(), "requests: " + requests);
        assertEquals("o,employeeType,l,branch,title,street,mail,telephoneNumber,departmentNumber",
                requestedAttributes(requests.get(0)));
        assertTrue(requestedAttributes(requests.get(1)).startsWith("o,employeeType,l,"), requests.get(1));
        assertTrue(requestedAttributes(requests.get(2)).startsWith("1.1"), "search() only needs names: " + requests.get(2));
    }

    /** The group tree reads each group's members once and lists children by name only. */
    @Test
    void theGroupTreeReadsEachGroupOnce() {
        LdapGroup engineering = ldapService.buildGroupRecursiveInternal(ENGINEERING);

        assertEquals(2, engineering.getGroupSize());
        assertEquals(1, engineering.getChildren().size());
        // Engineering: members + children; Platform: members + children.
        List<String> requests = directory.searchRequests();
        assertEquals(4, requests.size(), "requests: " + requests);
        assertEquals(2, requests.stream().filter(r -> requestedAttributes(r).equals("uniqueMember")).count(), "requests: " + requests);
    }

    // --- group membership cache -------------------------------------------------------------------------------

    @Test
    void aUsersGroupsAreReadFromTheDirectoryOnceWithinTheCachePeriod() {
        List<LdapGroup> first = ldapService.findGroupsForUser(BOB);
        int afterFirst = directory.searchRequests().size();
        List<LdapGroup> again = ldapService.findGroupsForUser(BOB.toUpperCase());

        assertEquals(2, first.size());
        assertEquals(first, again);
        assertEquals(afterFirst, directory.searchRequests().size(), "second call went to the directory");
        assertThrows(UnsupportedOperationException.class, () -> again.add(new LdapGroup()), "the cached list is shared");
    }

    @Test
    void theGroupCacheCanBeTurnedOff() {
        props.getLdap().setGroupMembershipCacheSeconds(0);
        LdapService uncached = new LdapService(directory.ldapTemplate(), props);

        uncached.findGroupsForUser(BOB);
        int afterFirst = directory.searchRequests().size();
        uncached.findGroupsForUser(BOB);

        assertTrue(directory.searchRequests().size() > afterFirst);
    }

    // --- org chart counts --------------------------------------------------------------------------------------

    /** Every org's counts from one scan, matching what the per-org search returned. */
    @Test
    void orgCountsForEveryValueComeFromOneScan() {
        Map<String, Map<String, Integer>> counts =
                ldapService.countByAttributeValueSplitOnAttribute(BASE, "departmentNumber", "employeeType");

        assertEquals(Map.of("FT", 1, "PT", 1), counts.get("RYS34B"));
        assertEquals(Map.of("FT", 1), counts.get("RYS34C"));
        assertEquals(Map.of("CON", 1), counts.get("ABC12X"));
        assertEquals(Map.of("FT", 1, "PT", 1), counts.get("rys34b"), "values match ignoring case, as LDAP equality does");
        assertEquals(counts.get("RYS34B"),
                ldapService.getTotalEntriesWithAttributeValueSplitOnAttribute(BASE, "departmentNumber", "RYS34B", "employeeType"));
    }
}
