package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.OrgNodeRestService;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs {@link LdapService}, and the endpoints that build LDAP filters, against a real directory.
 * <p>
 * The recurring failure mode here is a value that looks right in isolation but does not survive a
 * round trip: a DN that cannot be looked up again, a filter the server rejects, an escape that
 * changes what is matched. So most tests take what one call returns and feed it to the next.
 */
class LdapServiceDirectoryTest {

    private static final String BASE = InMemoryDirectory.BASE_DN;
    private static final String ALICE = "cn=Alice Adams,ou=Users," + BASE;
    private static final String BOB = "cn=Bob Barker,ou=Users," + BASE;
    private static final String CAROL = "cn=Carol Clark,ou=Users," + BASE;
    private static final String JANE = "cn=Doe\\, Jane,ou=Users," + BASE;
    private static final String STARMAN = "cn=Star*Man,ou=Users," + BASE;
    private static final String ENGINEERING = "cn=Engineering,ou=Groups," + BASE;
    private static final String PLATFORM = "cn=Platform,cn=Engineering,ou=Groups," + BASE;
    private static final String SALES = "cn=Sales,ou=Groups," + BASE;

    private static InMemoryDirectory directory;

    private LdapService ldapService;
    private ApplicationProperties props;
    private UserService userService;
    private final MockHttpSession session = new MockHttpSession();

    @BeforeAll
    static void startDirectory() {
        directory = InMemoryDirectory.start();
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    @BeforeEach
    void setUp() {
        // Mirrors application.yml.
        props = new ApplicationProperties();
        props.setUserBaseDn(BASE);
        props.setUserLdapFilter("(objectclass=inetOrgPerson)");
        props.setUserLdapEmployeeTypeAttribute("employeeType");
        props.setUserLdapOrganizationAttribute("o");
        props.setUserLdapLocationAttribute("l");
        props.setUserLdapManagerIdAttribute("title");
        props.setManagerLdapIdAttribute("street");
        props.setUserLdapDutySubOrganizationAttribute("departmentNumber");
        TopLevelGroupProperties groups = new TopLevelGroupProperties();
        groups.setGroupsBaseDn("ou=Groups," + BASE);
        TopLevelGroupProperties companies = new TopLevelGroupProperties();
        companies.setGroupsBaseDn("ou=Companies," + BASE);
        props.setGroups(List.of(groups, companies));

        ldapService = new LdapService(directory.ldapTemplate(), props);

        // The endpoints enrich each hit through UserService; echo the DN so the tests see exactly
        // what the directory handed over.
        userService = mock(UserService.class);
        when(userService.getUserStatuses(any(), any()))
                .thenAnswer(inv -> ((java.util.Collection<String>) inv.getArgument(0)).stream()
                        .map(dn -> UserStatus.builder().dn(dn).build()).toList());
    }

    private UserRestService userRestService() {
        return new UserRestService(ldapService, mock(UserRecordRepository.class),
                mock(CheckInOutRecordRepository.class), props, userService);
    }

    private List<String> searchFor(String term) {
        return dns(userRestService().searchUsers(session, term, 1, 10, "id", "asc"));
    }

    private static List<String> dns(List<UserStatus> users) {
        return users.stream().map(UserStatus::getDn).sorted().toList();
    }

    private static List<String> sorted(String... dns) {
        return List.of(dns).stream().sorted().toList();
    }

    // --- search(): what the user search and org tables are built from -----------------------

    /** Every row is looked up again by DN for its status, notes and details link. */
    @Test
    void searchReturnsAbsoluteDnsThatLookUpTheSameEntry() {
        List<UserStatus> users = ldapService.search("(objectClass=inetOrgPerson)");

        assertEquals(5, users.size());
        for (UserStatus user : users) {
            Optional<LdapUser> found = ldapService.lookupUser(new LdapDn(user.getDn()));
            assertTrue(found.isPresent(), "search returned a DN that does not resolve: " + user.getDn());
            assertEquals(new LdapDn(user.getDn()), new LdapDn(found.get().getDn()));
        }
    }

    @Test
    void userSearchMatchesPartOfTheNameIgnoringCase() {
        assertEquals(List.of(BOB), searchFor("BARK"));
    }

    /** An empty term used to produce "objectClass=inetOrgPerson", which JNDI rejects outright. */
    @Test
    void userSearchWithNoTermIsAValidFilterListingEveryone() {
        assertEquals(sorted(ALICE, BOB, CAROL, JANE, STARMAN), searchFor(""));
    }

    @Test
    void userSearchTreatsAnAsteriskLiterally() {
        assertEquals(List.of(STARMAN), searchFor("r*M"));
    }

    @Test
    void userSearchCannotInjectFilterSyntax() {
        assertTrue(searchFor("Bob)(cn=*").isEmpty());
    }

    /** An escaped comma in a DN has to reach UserService intact, or its lookups miss. */
    @Test
    void userSearchKeepsAnEscapedCommaInTheDn() {
        List<String> result = searchFor("Doe");

        assertEquals(1, result.size());
        assertTrue(ldapService.lookupUser(new LdapDn(result.getFirst())).isPresent(),
                "escaped DN did not resolve: " + result.getFirst());
    }

    @Test
    void orgUsersMatchOnTheDutySubOrganizationAttribute() {
        OrgNodeRestService orgNodes = new OrgNodeRestService(mock(OrgChartService.class), props, ldapService, userService);

        assertEquals(sorted(ALICE, BOB), dns(orgNodes.getUsers(null, session, "RYS34B")));
        assertTrue(orgNodes.getUsers(null, session, "RYS34*").isEmpty(), "org name must match literally");
    }

    // --- user lookups ------------------------------------------------------------------------

    @Test
    void findAllUsersMapsTheConfiguredAttributes() {
        Map<String, LdapUser> byDn = ldapService.findAllUsers().stream()
                .collect(Collectors.toMap(LdapUser::getDn, u -> u));

        assertEquals(5, byDn.size());
        LdapUser bob = byDn.get(BOB);
        assertNotNull(bob, "findAllUsers keys: " + byDn.keySet());
        assertEquals("WinLLC", bob.getOrganization());
        assertEquals("PT", bob.getEmployeeType());
        assertEquals("New York", bob.getLocation());
        assertEquals("RYS34B", bob.getDutySubOrganization());
        assertEquals("MGR-100", bob.getManagerId());
        assertEquals("bob@winllc.com", bob.getEmail());

        LdapUser alice = byDn.get(ALICE);
        assertEquals("MGR-100", alice.getManagerLdapId());
        assertEquals("555-0100", alice.getPhoneNumber());
    }

    @Test
    void lookupUserByDnFindsTheEntry() {
        assertEquals(ALICE, ldapService.lookupUser(new LdapDn(ALICE)).orElseThrow().getDn());
    }

    /** DNs arrive from certificates and URLs in whatever case the sender used. */
    @Test
    void lookupUserIgnoresDnCase() {
        assertTrue(ldapService.lookupUser(new LdapDn("CN=alice adams,OU=users,DC=winllc,DC=com")).isPresent());
    }

    @Test
    void lookupUserForAMissingDnIsEmpty() {
        assertTrue(ldapService.lookupUser(new LdapDn("cn=Nobody,ou=Users," + BASE)).isEmpty());
    }

    /** How a user's manager is found: their title is the manager's street. */
    @Test
    void lookupUserByAttributeFindsTheManager() {
        LdapUser bob = ldapService.lookupUser(new LdapDn(BOB)).orElseThrow();

        Optional<LdapUser> manager = ldapService.lookupUser(props.getManagerLdapIdAttribute(), bob.getManagerId());

        assertEquals(ALICE, manager.orElseThrow().getDn());
    }

    @Test
    void findUsersReportingToReturnsTheManagersReports() {
        List<String> reports = ldapService.findUsersReportingTo("MGR-100").stream()
                .map(LdapUser::getDn).sorted().toList();

        assertEquals(sorted(BOB, CAROL), reports);
    }

    // --- groups --------------------------------------------------------------------------------

    @Test
    void findGroupsForUserReturnsEveryGroupUnderTheConfiguredBases() {
        List<String> groups = ldapService.findGroupsForUser(BOB).stream()
                .map(LdapGroup::getDn).sorted().toList();

        assertEquals(sorted(ENGINEERING, PLATFORM), groups);
    }

    @Test
    void findGroupsForUserMatchesAMemberWithAnEscapedComma() {
        List<String> groups = ldapService.findGroupsForUser(JANE).stream().map(LdapGroup::getDn).toList();

        assertEquals(List.of(SALES), groups);
    }

    /** Membership DNs are compared against search results, so the two must agree. */
    @Test
    void groupMembersAreTheSameDnsASearchReturns() {
        List<LdapDn> members = ldapService.getGroupMembers(new LdapDn(ENGINEERING)).stream()
                .map(LdapDn::new).toList();
        List<LdapDn> searched = ldapService.search("(|(cn=Alice Adams)(cn=Bob Barker))").stream()
                .map(u -> new LdapDn(u.getDn())).toList();

        assertEquals(2, members.size());
        assertTrue(members.containsAll(searched) && searched.containsAll(members),
                "members " + members + " vs search " + searched);
    }

    @Test
    void groupMembersOfAMissingGroupAreEmpty() {
        assertTrue(ldapService.getGroupMembers(new LdapDn("cn=Nope,ou=Groups," + BASE)).isEmpty());
    }

    @Test
    void lookupGroupMapsItsAttributes() {
        LdapGroup group = ldapService.lookupGroup(new LdapDn(ENGINEERING)).orElseThrow();

        assertEquals(ENGINEERING, group.getDn());
        assertEquals("Engineering", group.getCn());
        assertEquals("Builds things", group.getDescription());
        assertEquals(ALICE, group.getManager());
    }

    @Test
    void getGroupsListsTheGroupsUnderABase() {
        List<String> groups = ldapService.getGroups(props.getGroups().getFirst()).stream()
                .map(LdapGroup::getDn).sorted().toList();

        assertEquals(sorted(ENGINEERING, PLATFORM, SALES), groups);
    }

    @Test
    void getGroupsUnderAMissingBaseIsEmpty() {
        TopLevelGroupProperties missing = new TopLevelGroupProperties();
        missing.setGroupsBaseDn("ou=Missing," + BASE);

        assertTrue(ldapService.getGroups(missing).isEmpty());
    }

    /** Child DNs from the one-level search are fed straight back in, so they must resolve. */
    @Test
    void groupHierarchyResolvesNestedGroups() {
        LdapGroup engineering = ldapService.buildGroupRecursiveInternal(ENGINEERING);

        assertNotNull(engineering);
        assertEquals(2, engineering.getGroupSize());
        assertEquals(1, engineering.getChildren().size(), "children: " + engineering.getChildren());
        LdapGroup platform = engineering.getChildren().getFirst();
        assertEquals(new LdapDn(PLATFORM), new LdapDn(platform.getDn()));
        assertEquals("Platform", platform.getName());
        assertEquals(1, platform.getGroupSize());
    }

    @Test
    void groupHierarchyForAMissingDnIsNull() {
        assertNull(ldapService.buildGroupRecursiveInternal("cn=Nope,ou=Groups," + BASE));
    }

    // --- counting --------------------------------------------------------------------------------

    @Test
    void countWithSplitGroupsEntriesByTheAttribute() {
        Map<String, Integer> counts = ldapService.countWithSplit("ou=Users," + BASE,
                "(objectClass=inetOrgPerson)", "departmentNumber");

        assertEquals(Map.of("RYS34B", 2, "RYS34C", 1, "ABC12X", 1, "EMPTY", 1), counts);
    }

    @Test
    void entriesWithAnAttributeValueAreSplitByAnother() {
        Map<String, Integer> counts = ldapService.getTotalEntriesWithAttributeValueSplitOnAttribute(
                BASE, "o", "WinLLC", "employeeType");

        assertEquals(Map.of("FT", 2, "PT", 1, "CON", 1), counts);
    }

    @Test
    void uniqueAttributeValuesAreDeduplicated() {
        List<String> values = ldapService.getAllUniqueValuesForAttributes("departmentNumber", null)
                .stream().sorted().toList();

        assertEquals(List.of("ABC12X", "RYS34B", "RYS34C"), values);
    }

    @Test
    void countMatchesTheFilter() {
        assertEquals(2, ldapService.count(BASE, "(title=MGR-100)"));
    }
}
