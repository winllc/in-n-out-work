package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.GroupController;
import com.winllc.innoutwork.controller.HomeController;
import com.winllc.innoutwork.controller.NotificationController;
import com.winllc.innoutwork.controller.UserController;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.GroupRestService;
import com.winllc.innoutwork.rest.OrgNodeRestService;
import com.winllc.innoutwork.rest.PermissionsRestService;
import com.winllc.innoutwork.rest.UserEventRestService;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.DirectReportsService;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.HomeService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.NotificationService;
import com.winllc.innoutwork.service.OrgChartService;
import com.winllc.innoutwork.service.PermissionService;
import com.winllc.innoutwork.service.ReportService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every endpoint that returns or changes one person's data asks {@link AccessPolicy}, and is refused (403) when it
 * says no. The policy is stubbed here; AccessPolicyTest decides who it says yes to. Lists that anyone may read (a
 * search, a group's or an organization's members) are not refused but show the rows the viewer may not see in
 * detail as presence only.
 */
@WebMvcTest
@Import({UserRestService.class, UserEventRestService.class, OrgNodeRestService.class, GroupRestService.class,
        PermissionsRestService.class, NotificationController.class, UserController.class, HomeController.class,
        GroupController.class, ScopedEndpointsTest.TestSecurityConfig.class})
class ScopedEndpointsTest {

    private static final String VIEWER = "CN=viewer,OU=Users,DC=winllc,DC=com";
    private static final String MANAGER = "CN=manager,OU=Users,DC=winllc,DC=com";
    private static final String TARGET = "CN=target,OU=Users,DC=winllc,DC=com";
    private static final String OTHER = "CN=other,OU=Users,DC=winllc,DC=com";
    private static final String GROUP = "cn=Engineering,ou=Groups,dc=winllc,dc=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "accessPolicy") AccessPolicy accessPolicy;
    @MockitoBean LdapService ldapService;
    @MockitoBean UserService userService;
    @MockitoBean UserRecordRepository userRecordRepository;
    @MockitoBean CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean UserEventRecordRepository userEventRecordRepository;
    @MockitoBean GlobalCalendarRecordRepository globalCalendarRecordRepository;
    @MockitoBean OrgChartService orgChartService;
    @MockitoBean GroupRecordRepository groupRecordRepository;
    @MockitoBean GroupService groupService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean NotificationRepository notificationRepository;
    @MockitoBean NotificationService notificationService;
    @MockitoBean DirectReportsService directReportsService;
    @MockitoBean CacheService cacheService;
    @MockitoBean HomeService homeService;
    @MockitoBean ReportService reportService;
    @MockitoBean com.winllc.innoutwork.service.StatusPolicy statusPolicy;
    @MockitoBean com.winllc.innoutwork.service.StatusCalendar statusCalendar;
    @MockitoBean com.winllc.innoutwork.service.StatusRequestService statusRequestService;
    @MockitoBean com.winllc.innoutwork.service.Approvers approvers;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            // CSRF is CsrfProtectionTest's subject; here it is who may see what.
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        /** "CN=manager" holds the MANAGER role the group-management pages also require; everyone else USER. */
        @Bean
        UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(username.startsWith("CN=manager")
                            ? new String[]{UserRoleEnum.USER.name(), UserRoleEnum.MANAGER.name()}
                            : new String[]{UserRoleEnum.USER.name()})
                    .build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }

    /** Which of the policy's answers an endpoint turns on. */
    enum Check { DETAILS, VIEW_GROUP, MANAGE_GROUP, NOTIFICATION }

    record Endpoint(String name, Check check, String viewer, Supplier<MockHttpServletRequestBuilder> request,
                    int allowedStatus) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Endpoint> endpoints() {
        String from = "2026-10-01T00:00:00Z";
        String to = "2026-11-01T00:00:00Z";
        return Stream.of(
                new Endpoint("GET /api/users/checkinoutrecords/{dn}", Check.DETAILS, VIEWER,
                        () -> get("/api/users/checkinoutrecords/{dn}", TARGET), 200),
                new Endpoint("GET /api/users/managers/{dn}", Check.DETAILS, VIEWER,
                        () -> get("/api/users/managers/{dn}", TARGET), 200),
                new Endpoint("GET /api/event/all", Check.DETAILS, VIEWER,
                        () -> get("/api/event/all").param("dn", TARGET).param("from", from).param("to", to), 200),
                new Endpoint("GET /api/event/allWithLogins", Check.DETAILS, VIEWER,
                        () -> get("/api/event/allWithLogins").param("dn", TARGET).param("from", from).param("to", to), 200),
                new Endpoint("GET /api/users/group/{group}", Check.VIEW_GROUP, VIEWER,
                        () -> get("/api/users/group/{group}", GROUP), 200),
                new Endpoint("GET /app/users/{group}", Check.VIEW_GROUP, VIEWER,
                        () -> get("/app/users/{group}", GROUP), 200),
                new Endpoint("GET /api/groups/managers/{group}", Check.VIEW_GROUP, MANAGER,
                        () -> get("/api/groups/managers/{group}", GROUP), 200),
                new Endpoint("POST /api/groups/managers/update/{group}", Check.MANAGE_GROUP, MANAGER,
                        () -> post("/api/groups/managers/update/{group}", GROUP)
                                .contentType(MediaType.APPLICATION_JSON).content("[\"" + OTHER + "\"]"), 200),
                new Endpoint("POST /api/permissions/update", Check.MANAGE_GROUP, MANAGER,
                        () -> post("/api/permissions/update").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userDn\":\"" + OTHER + "\",\"groupDn\":\"" + GROUP + "\",\"selected\":true}"), 200),
                new Endpoint("POST /app/group/report", Check.MANAGE_GROUP, MANAGER,
                        () -> post("/app/group/report").param("groupDn", GROUP), 200),
                new Endpoint("POST /app/group/report/exported", Check.MANAGE_GROUP, MANAGER,
                        () -> post("/app/group/report/exported").param("groupDn", GROUP), 204),
                new Endpoint("GET /app/notifications/id/{id}", Check.NOTIFICATION, VIEWER,
                        () -> get("/app/notifications/id/{id}", 42), 200));
    }

    @BeforeEach
    void setUp() {
        when(checkInOutRecordRepository.findByDnIgnoreCaseOrderByTimestampDesc(any(), anyString())).thenReturn(Page.empty());
        when(cacheService.getGroup(anyString())).thenReturn(new LdapGroup(GROUP, "Engineering"));
        when(groupService.getOrCreateGroupRecord(anyString())).thenAnswer(inv -> {
            GroupRecord record = new GroupRecord();
            record.setGroupDn(inv.getArgument(0));
            return record;
        });
        when(groupRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(notificationRepository.findById(42L)).thenReturn(Optional.of(NotificationRecord.builder().id(42L)
                .forUserDn(VIEWER).aboutUserDn(TARGET).type(NotificationTypeEnum.ABSENT)
                .notificationDate(ZonedDateTime.now()).build()));
    }

    private void policySays(Check check, boolean answer) {
        switch (check) {
            case DETAILS -> when(accessPolicy.canViewDetails(any(), anyString())).thenReturn(answer);
            case VIEW_GROUP -> when(accessPolicy.canViewGroup(any(), anyString())).thenReturn(answer);
            case MANAGE_GROUP -> when(accessPolicy.canManageGroup(any(), anyString())).thenReturn(answer);
            case NOTIFICATION -> when(accessPolicy.canOpenNotification(any(), any())).thenReturn(answer);
        }
        when(accessPolicy.scope(any())).thenReturn(AccessPolicy.Scope.of(dn -> answer));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void allowedWhenThePolicyAllows(Endpoint endpoint) throws Exception {
        policySays(endpoint.check(), true);

        mockMvc.perform(endpoint.request().get().with(x509(cert(endpoint.viewer()))))
                .andExpect(status().is(endpoint.allowedStatus()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void refusedWhenThePolicyRefuses(Endpoint endpoint) throws Exception {
        policySays(endpoint.check(), false);

        mockMvc.perform(endpoint.request().get().with(x509(cert(endpoint.viewer()))))
                .andExpect(status().isForbidden());
    }

    /** Refused before anything is changed or read. */
    @Test
    void aRefusedChangeChangesNothing() throws Exception {
        policySays(Check.MANAGE_GROUP, false);

        mockMvc.perform(post("/api/groups/managers/update/{group}", GROUP).with(x509(cert(MANAGER)))
                        .contentType(MediaType.APPLICATION_JSON).content("[\"" + MANAGER + "\"]"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/permissions/update").with(x509(cert(MANAGER))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userDn\":\"" + MANAGER + "\",\"groupDn\":\"" + GROUP + "\",\"selected\":true}"))
                .andExpect(status().isForbidden());

        verify(groupRecordRepository, never()).save(any());
        verify(permissionService, never()).addGroupToUser(any(), any());
    }

    // --- lists anyone may read: presence for everyone, details for the viewer's people ----------------------

    private void statusesAreFull() {
        when(userService.getUserStatuses(any(), any())).thenAnswer(inv -> inv.<Collection<String>>getArgument(0).stream()
                .map(dn -> UserStatus.builder().dn(dn).status("IN").notes("notes of " + dn)
                        .checkedInAt(ZonedDateTime.now()).lastStatusChangeAt(ZonedDateTime.now()).build())
                .toList());
        // The viewer is responsible for TARGET and nobody else.
        when(accessPolicy.scope(any())).thenReturn(AccessPolicy.Scope.of(Set.of(TARGET)::contains));
    }

    private void assertOnlyTargetsDetailsShown(JsonNode rows) {
        assertEquals(2, rows.size(), rows.toString());
        for (JsonNode row : rows) {
            boolean detailed = TARGET.equals(row.get("dn").asString());
            assertEquals("IN", row.get("status").asString(), "presence is for everyone");
            assertEquals(detailed, row.hasNonNull("notes"), row.toString());
            assertEquals(detailed, row.hasNonNull("checkedInAt"), row.toString());
            assertEquals(detailed, row.hasNonNull("lastStatusChangeAt"), row.toString());
        }
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mockMvc.perform(request.with(x509(cert(VIEWER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    @Test
    void coworkerSearchShowsPresenceToEveryoneAndDetailsOnlyWhereAllowed() throws Exception {
        statusesAreFull();
        when(ldapService.search(anyString())).thenReturn(List.of(
                UserStatus.builder().dn(TARGET).build(), UserStatus.builder().dn(OTHER).build()));

        assertOnlyTargetsDetailsShown(json(get("/api/users/search").param("search", "tar")));
    }

    @Test
    void aGroupsMembersShowDetailsOnlyWhereAllowed() throws Exception {
        statusesAreFull();
        when(accessPolicy.canViewGroup(any(), anyString())).thenReturn(true);
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(TARGET, OTHER));

        assertOnlyTargetsDetailsShown(json(get("/api/users/group/{group}", GROUP)).get("data"));
    }

    @Test
    void anOrganizationsMembersShowDetailsOnlyWhereAllowed() throws Exception {
        statusesAreFull();
        when(ldapService.search(anyString())).thenReturn(List.of(
                UserStatus.builder().dn(TARGET).build(), UserStatus.builder().dn(OTHER).build()));

        assertOnlyTargetsDetailsShown(json(get("/api/orgnodes/users/{org}", "Engineering")));
    }

    // --- a person's page ----------------------------------------------------------------------------------

    private void detailsPage() {
        when(userService.getUserDetails(any(), any())).thenReturn(UserStatus.builder().dn(TARGET).status("IN")
                .notes("dentist at 3").averageLoginTime("08:30").organization("Eng").build());
    }

    @Test
    void aCoworkersPageShowsPresenceButNotTheirAttendance() throws Exception {
        detailsPage();
        when(accessPolicy.canViewDetails(any(), anyString())).thenReturn(false);

        UserStatus shown = (UserStatus) mockMvc.perform(get("/app/user/details/{dn}", TARGET).with(x509(cert(VIEWER))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isGroupManager", false))
                .andReturn().getModelAndView().getModel().get("user");

        assertEquals("IN", shown.getStatus());
        assertEquals("Eng", shown.getOrganization());
        assertEquals(null, shown.getNotes());
        assertEquals(null, shown.getAverageLoginTime());
    }

    @Test
    void theirManagersPageShowsEverything() throws Exception {
        detailsPage();
        when(accessPolicy.canViewDetails(any(), anyString())).thenReturn(true);

        UserStatus shown = (UserStatus) mockMvc.perform(get("/app/user/details/{dn}", TARGET).with(x509(cert(VIEWER))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isGroupManager", true))
                .andReturn().getModelAndView().getModel().get("user");

        assertEquals("dentist at 3", shown.getNotes());
        assertEquals("08:30", shown.getAverageLoginTime());
    }

    /** The group page offers its managers and report only to those who may use them. */
    @Test
    void theGroupPagesManagementCardsAreForItsManagersOnly() throws Exception {
        when(accessPolicy.canViewGroup(any(), anyString())).thenReturn(true);

        when(accessPolicy.canManageGroup(any(), anyString())).thenReturn(false);
        String page = mockMvc.perform(get("/app/users/{group}", GROUP).with(x509(cert(MANAGER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(page.contains("id=\"group-managers\""), "managers card for someone who does not manage the group");

        when(accessPolicy.canManageGroup(any(), anyString())).thenReturn(true);
        page = mockMvc.perform(get("/app/users/{group}", GROUP).with(x509(cert(MANAGER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("id=\"group-managers\""));
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
