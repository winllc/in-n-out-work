package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Who may see whom, against a small organisation held in mocks:
 * <pre>
 *   Engineering  owner Owen (directory)           members Bob, Carol
 *   Sales        alternate manager Alma (ReadyRoom) member  Dave
 *   Mary         directory manager of Erin
 *   Frank        named Alton as his alternate manager
 *   Mike         MANAGER role, nothing else
 *   Ada          ADMIN
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessPolicyTest {

    private static final Map<String, String> PEOPLE = Map.ofEntries(
            entry("ADA", "cn=Ada,ou=Users,dc=winllc,dc=com"),
            entry("ALMA", "cn=Alma,ou=Users,dc=winllc,dc=com"),
            entry("ALTON", "cn=Alton,ou=Users,dc=winllc,dc=com"),
            entry("BOB", "cn=Bob,ou=Users,dc=winllc,dc=com"),
            entry("CAROL", "cn=Carol,ou=Users,dc=winllc,dc=com"),
            entry("DAVE", "cn=Dave,ou=Users,dc=winllc,dc=com"),
            entry("ERIN", "cn=Erin,ou=Users,dc=winllc,dc=com"),
            entry("FRANK", "cn=Frank,ou=Users,dc=winllc,dc=com"),
            entry("MARY", "cn=Mary,ou=Users,dc=winllc,dc=com"),
            entry("MIKE", "cn=Mike,ou=Users,dc=winllc,dc=com"),
            entry("OWEN", "cn=Owen,ou=Users,dc=winllc,dc=com"));
    private static final Map<String, String> GROUPS = Map.of(
            "ENGINEERING", "cn=Engineering,ou=Groups,dc=winllc,dc=com",
            "SALES", "cn=Sales,ou=Groups,dc=winllc,dc=com");

    @Mock private PermissionService permissionService;
    @Mock private LdapService ldapService;
    @Mock private GroupRecordRepository groupRecords;
    @Mock private UserService userService;
    @Mock private UserRecordRepository userRecords;

    private AccessPolicy policy;

    private static String dn(String name) {
        return PEOPLE.get(name);
    }

    private static String group(String name) {
        return GROUPS.get(name);
    }

    private static Authentication as(String name) {
        String role = switch (name) {
            case "ADA" -> "ADMIN";
            case "MIKE" -> "MANAGER";
            default -> "USER";
        };
        // As AppUserDetailsService names them: the DN, here upper-cased to show case does not matter.
        return new TestingAuthenticationToken(dn(name).toUpperCase(), null, "USER", role);
    }

    /** What a LIKE ... escape '!' pattern from AccessPolicy matches, for the repository stubs. */
    private static boolean like(String text, String pattern) {
        String needle = pattern.substring(1, pattern.length() - 1).replaceAll("!(.)", "$1");
        return text != null && text.toLowerCase().contains(needle);
    }

    @BeforeEach
    void setUp() {
        policy = new AccessPolicy(permissionService, ldapService, new GroupService(ldapService, groupRecords),
                userService, userRecords, groupRecords, new com.winllc.innoutwork.config.ApplicationProperties());

        LdapGroup engineering = new LdapGroup(group("ENGINEERING"), "Engineering");
        engineering.setManager(dn("OWEN"));
        LdapGroup sales = new LdapGroup(group("SALES"), "Sales");
        GroupRecord salesManagers = new GroupRecord();
        salesManagers.setGroupDn(group("SALES"));
        salesManagers.addAltManager(dn("ALMA"));

        when(ldapService.lookupGroup(any())).thenAnswer(inv -> Optional.ofNullable(
                Map.of(group("ENGINEERING"), engineering, group("SALES"), sales).get(inv.<LdapDn>getArgument(0).dn())));
        when(ldapService.findGroupsOwnedBy(anyString())).thenAnswer(inv ->
                AccessPolicy.sameDn(inv.getArgument(0), dn("OWEN")) ? List.of(engineering) : List.of());
        when(ldapService.getGroupMembers(any())).thenAnswer(inv -> Map.of(
                group("ENGINEERING"), List.of(dn("BOB"), dn("CAROL")),
                group("SALES"), List.of(dn("DAVE"))).getOrDefault(inv.<LdapDn>getArgument(0).dn(), List.of()));
        when(permissionService.getUserGroupPermissionsAndMemberOfGroups(any())).thenAnswer(inv -> {
            String who = inv.<LdapDn>getArgument(0).dn();
            if (AccessPolicy.sameDn(who, dn("BOB")) || AccessPolicy.sameDn(who, dn("CAROL"))) {
                return List.of(new LdapDn(group("ENGINEERING")));
            }
            return AccessPolicy.sameDn(who, dn("DAVE")) ? List.of(new LdapDn(group("SALES"))) : List.of();
        });

        when(groupRecords.findByGroupDnIgnoreCase(anyString())).thenAnswer(inv ->
                AccessPolicy.sameDn(inv.getArgument(0), group("SALES")) ? Optional.of(salesManagers) : Optional.empty());
        when(groupRecords.findByAlternateManagersLike(anyString())).thenAnswer(inv ->
                like(salesManagers.getAlternateManagers(), inv.getArgument(0)) ? List.of(salesManagers) : List.of());

        UserRecord frank = UserRecord.builder().dn(dn("FRANK")).build();
        frank.addAltManager(dn("ALTON"));
        when(userRecords.findByAlternateManagersLike(anyString())).thenAnswer(inv ->
                like(frank.getAlternateManagers(), inv.getArgument(0)) ? List.of(frank) : List.of());

        when(userService.findDirectReports(any())).thenAnswer(inv ->
                AccessPolicy.sameDn(inv.<LdapDn>getArgument(0).dn(), dn("MARY"))
                        ? List.of(LdapUser.builder().dn(dn("ERIN")).build()) : List.of());
    }

    // --- details ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} sees {1}''s details: {2}")
    @CsvSource({
            "BOB,   BOB,   true",   // self
            "ADA,   DAVE,  true",   // ADMIN
            "OWEN,  BOB,   true",   // owner of Bob's group
            "ALMA,  DAVE,  true",   // alternate manager of Dave's group
            "MARY,  ERIN,  true",   // Erin's manager in the directory
            "ALTON, FRANK, true",   // Frank named Alton his alternate manager
            "BOB,   CAROL, false",  // a fellow member
            "MIKE,  BOB,   false",  // the MANAGER role alone
            "OWEN,  DAVE,  false",  // owner of another group
            "MARY,  FRANK, false",
            "ERIN,  MARY,  false",  // a report does not see their manager
            "FRANK, ALTON, false",  // naming someone shows them your details, not theirs to you
    })
    void details(String viewer, String target, boolean expected) {
        assertEquals(expected, policy.canViewDetails(as(viewer), dn(target)));
        assertEquals(expected, policy.scope(as(viewer)).canViewDetails(dn(target)), "the scope agrees");
    }

    @Test
    void nobodySignedInSeesNothing() {
        assertFalse(policy.canViewDetails(null, dn("BOB")));
        assertFalse(policy.canViewDetails(new TestingAuthenticationToken(dn("BOB"), null), dn("BOB")),
                "not authenticated");
    }

    @Test
    void lookingAtYourselfReadsNothingElse() {
        assertTrue(policy.scope(as("BOB")).canViewDetails(dn("BOB")));

        verifyNoInteractions(ldapService, userService, userRecords, groupRecords);
    }

    /** One search, one group's members: the people a viewer is responsible for are read once, not per row. */
    @Test
    void aScopeReadsTheViewersPeopleOnce() {
        AccessPolicy.Scope scope = policy.scope(as("OWEN"));

        for (String person : List.of("BOB", "CAROL", "DAVE", "ERIN", "FRANK")) {
            scope.canViewDetails(dn(person));
        }

        verify(ldapService, times(1)).findGroupsOwnedBy(anyString());
        verify(userService, times(1)).findDirectReports(any());
        verify(userRecords, times(1)).findByAlternateManagersLike(anyString());
    }

    // --- what a coworker sees -----------------------------------------------------------------------------

    @Test
    void aCoworkerSeesPresenceButNoTimesNotesOrRole() {
        UserStatus full = UserStatus.builder().dn(dn("CAROL")).status("IN").role("MANAGER").notes("dentist at 3")
                .checkedInAt(ZonedDateTime.now()).checkedOutAt(ZonedDateTime.now()).lastStatusChangeAt(ZonedDateTime.now())
                .averageLoginTime("08:30").organization("Eng").location("HQ").email("carol@winllc.com")
                .build();

        UserStatus shown = policy.scope(as("BOB")).show(full);

        assertEquals("IN", shown.getStatus());
        assertEquals("Carol", shown.getCn());
        assertEquals("Eng", shown.getOrganization());
        assertEquals("carol@winllc.com", shown.getEmail());
        assertNull(shown.getCheckedInAt());
        assertNull(shown.getCheckedOutAt());
        assertNull(shown.getLastStatusChangeAt());
        assertNull(shown.getNotes());
        assertNull(shown.getAverageLoginTime());
        assertNull(shown.getRole());

        assertSame(full, policy.scope(as("OWEN")).show(full), "her group's owner sees it all");
    }

    @ParameterizedTest
    @CsvSource({
            "IN,                IN",
            "AWAY,              AWAY",
            "TDY,               TDY",
            "WORK_FROM_HOME,    WORK_FROM_HOME",
            "SCHEDULED_LEAVE,   SCHEDULED_LEAVE",
            "ABSENT_UNEXCUSED,  OUT_OF_OFFICE",
            "UNSCHEDULED_LEAVE, OUT_OF_OFFICE",
            "ABSENT,            OUT_OF_OFFICE",
    })
    void aStatusAManagerRecordedReadsAsOutOfOfficeToCoworkers(String status, String shown) {
        assertEquals(shown, AccessPolicy.presenceOnly(UserStatus.builder().dn(dn("CAROL")).status(status).build()).getStatus());
    }

    // --- application.privacy.coworker-visibility -----------------------------------------------------------

    private AccessPolicy policyWith(CoworkerVisibility visibility) {
        com.winllc.innoutwork.config.ApplicationProperties properties = new com.winllc.innoutwork.config.ApplicationProperties();
        properties.getPrivacy().setCoworkerVisibility(visibility);
        return new AccessPolicy(permissionService, ldapService, new GroupService(ldapService, groupRecords),
                userService, userRecords, groupRecords, properties);
    }

    private static UserStatus fullStatus(String status, String presence) {
        return fullStatus("CAROL", status, presence);
    }

    private static UserStatus fullStatus(String who, String status, String presence) {
        return UserStatus.builder().dn(dn(who)).status(status).presence(presence).notes("dentist")
                .checkedInAt(java.time.ZonedDateTime.now()).averageLoginTime("08:00").role("USER")
                .organization("Eng").email("carol@example.com").build();
    }

    @ParameterizedTest(name = "status {0} (presence {1}) shows as {2}")
    @CsvSource({
            "IN,             IN,   IN",
            "AWAY,           AWAY, IN",
            "OUT,            OUT,  OUT",
            "NONE,           NONE, OUT",
            "WORK_FROM_HOME, IN,   IN",
            "WORK_FROM_HOME, NONE, OUT",
            "ABSENT,         NONE, OUT",
    })
    void underStatusCoworkersSeeOnlyInOrOut(String status, String presence, String shown) {
        UserStatus seen = AccessPolicy.coworkerView(fullStatus(status, presence), CoworkerVisibility.STATUS);

        assertEquals(shown, seen.getStatus());
        assertEquals("Eng", seen.getOrganization(), "who they are stays");
        assertNull(seen.getNotes());
        assertNull(seen.getCheckedInAt());
        assertNull(seen.getAverageLoginTime());
        assertNull(seen.getRole());
    }

    @Test
    void underNoneCoworkersSeeNothingOfAttendance() {
        UserStatus seen = AccessPolicy.coworkerView(fullStatus("SCHEDULED_LEAVE", "NONE"), CoworkerVisibility.NONE);

        assertNull(seen.getStatus());
        assertNull(seen.getNotes());
        assertNull(seen.getCheckedInAt());
        assertEquals("carol@example.com", seen.getEmail());
    }

    @Test
    void underFullCoworkersSeeTheDaysStatusAsBefore() {
        UserStatus seen = AccessPolicy.coworkerView(fullStatus("WORK_FROM_HOME", "NONE"), CoworkerVisibility.FULL);

        assertEquals("WORK_FROM_HOME", seen.getStatus());
        assertNull(seen.getNotes());
        assertNull(seen.getCheckedInAt());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CoworkerVisibility.class)
    void theScopeShowsCoworkersAsConfiguredAndTheirOwnPeopleInFull(CoworkerVisibility visibility) {
        AccessPolicy configured = policyWith(visibility);
        AccessPolicy.Scope owen = configured.scope(as("OWEN"));

        UserStatus report = owen.show(fullStatus("BOB", "WORK_FROM_HOME", "IN"));
        UserStatus coworker = owen.show(fullStatus("DAVE", "WORK_FROM_HOME", "IN"));

        assertEquals("dentist", report.getNotes(), "Owen manages Engineering, Bob's group: details in full");
        assertEquals("WORK_FROM_HOME", report.getStatus());
        assertNull(coworker.getNotes());
        assertEquals(switch (visibility) {
            case FULL -> "WORK_FROM_HOME";
            case STATUS -> "IN";
            case NONE -> null;
        }, coworker.getStatus());
        assertEquals("dentist", configured.scope(as("ADA")).show(fullStatus("TDY", "IN")).getNotes(),
                "administrators see everyone in full");
    }

    @Test
    void presenceCountsAreHiddenUnderNoneFromAllButManagersAndAdministrators() {
        assertTrue(policyWith(CoworkerVisibility.STATUS).seesPresenceCounts(as("BOB")));
        AccessPolicy none = policyWith(CoworkerVisibility.NONE);
        assertFalse(none.seesPresenceCounts(as("BOB")));
        assertTrue(none.seesPresenceCounts(as("ADA")));
        assertTrue(none.seesPresenceCounts(as("MIKE")));
    }

    // --- groups -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} opens {1}: {2}")
    @CsvSource({
            "BOB,   ENGINEERING, true",   // member
            "OWEN,  ENGINEERING, true",   // owner, not a member
            "ALMA,  SALES,       true",   // alternate manager
            "ADA,   SALES,       true",   // ADMIN
            "CAROL, SALES,       false",
            "MIKE,  ENGINEERING, false",  // the MANAGER role alone
    })
    void viewingAGroup(String viewer, String groupName, boolean expected) {
        assertEquals(expected, policy.canViewGroup(as(viewer), group(groupName)));
    }

    @ParameterizedTest(name = "{0} manages {1}: {2}")
    @CsvSource({
            "OWEN, ENGINEERING, true",
            "ALMA, SALES,       true",
            "ADA,  ENGINEERING, true",
            "BOB,  ENGINEERING, false",  // a member does not manage it
            "MIKE, SALES,       false",  // the MANAGER role alone
            "OWEN, SALES,       false",  // managing one group is not managing another
    })
    void managingAGroup(String viewer, String groupName, boolean expected) {
        assertEquals(expected, policy.canManageGroup(as(viewer), group(groupName)));
    }

    @Test
    void aMalformedGroupDnIsRefusedRatherThanAnError() {
        assertFalse(policy.canViewGroup(as("BOB"), "not a dn"));
        assertFalse(policy.canManageGroup(as("OWEN"), "not a dn"));
    }

    @Test
    void theGroupsPageListsTheGroupsAViewerIsInOrManages() {
        assertEquals(List.of(new LdapDn(group("ENGINEERING"))), policy.viewableGroups(as("BOB")));
        assertEquals(List.of(new LdapDn(group("ENGINEERING"))), policy.viewableGroups(as("OWEN")));
        assertEquals(List.of(new LdapDn(group("SALES"))), policy.viewableGroups(as("ALMA")));
        assertEquals(List.of(), policy.viewableGroups(as("MIKE")));
    }

    // --- notifications ------------------------------------------------------------------------------------

    @Test
    void aNotificationIsForItsRecipientAndAdministrators() {
        NotificationRecord alert = NotificationRecord.builder().id(7L).forUserDn(dn("MARY")).aboutUserDn(dn("ERIN")).build();

        assertTrue(policy.canOpenNotification(as("MARY"), alert));
        assertTrue(policy.canOpenNotification(as("ADA"), alert));
        assertFalse(policy.canOpenNotification(as("ERIN"), alert), "not even the person it is about");
        assertFalse(policy.canOpenNotification(as("MIKE"), alert));
        assertFalse(policy.canOpenNotification(as("MARY"), null), "a missing one is refused like another's");
    }
}
