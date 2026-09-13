package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.TopLevelGroupProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.HomeService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.data.ExpectedLogin;
import com.winllc.innoutwork.data.home.AttendanceSummary;
import com.winllc.innoutwork.data.home.HomeDashboard;
import com.winllc.innoutwork.data.home.NotInYet;
import com.winllc.innoutwork.data.home.PersonalSummary;
import com.winllc.innoutwork.data.home.TeamSummary;
import com.winllc.innoutwork.data.home.UpcomingStatus;
import com.winllc.innoutwork.data.metrics.AccountabilityMetrics;
import com.winllc.innoutwork.data.metrics.AccountedFor;
import com.winllc.innoutwork.data.metrics.AgentCoverage;
import com.winllc.innoutwork.data.metrics.StatusMixEntry;
import com.winllc.innoutwork.data.metrics.StoppedAgent;
import com.winllc.innoutwork.data.metrics.UserRef;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static com.winllc.innoutwork.controller.ProfileControllerTest.mockCert;
import static org.mockito.ArgumentMatchers.any;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * See {@link ProfileControllerTest} for why the controller has to be imported explicitly.
 */
@WebMvcTest(HomeController.class)
@Import({HomeController.class, HomeControllerTest.TestSecurityConfig.class})
class HomeControllerTest {

    private static final String USER_DN = "CN=alice,OU=Test";
    private static final String GROUPS_BASE_DN = "ou=Groups,dc=winllc,dc=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserDetailsService appUserDetailsService;
    @MockitoBean
    private CacheService cacheService;
    @MockitoBean
    private ApplicationProperties properties;
    @MockitoBean
    private UserRecordRepository userRecordRepository;
    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private LdapService ldapService;
    @MockitoBean
    private HomeService homeService;
    /** Referenced by name from the @PreAuthorize expression on /app/users/{group}. */
    @MockitoBean(name = "permissionEvaluator")
    private com.winllc.innoutwork.security.PermissionEvaluator permissionEvaluator;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    // Mirror SecurityConfig: the full subject DN is the username.
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)")
                            .userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        public UserDetailsService userDetailsService() {
            return username -> User.withUsername(username)
                    .password("")
                    .authorities(UserRoleEnum.USER.name())
                    .build();
        }
    }

    @Test
    void rootRedirectsToHome() throws Exception {
        mockMvc.perform(get("/").with(x509(mockCert(USER_DN))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/home"));
    }

    @Test
    void appRedirectsToHome() throws Exception {
        mockMvc.perform(get("/app").with(x509(mockCert(USER_DN))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app/home"));
    }

    // --- home page ---------------------------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.now();
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";

    private static PersonalSummary me(boolean agentQuiet) {
        ZonedDateTime in = TODAY.atTime(8, 5).atZone(ZoneId.systemDefault());
        return new PersonalSummary(TODAY, "Checked in", "bg-green-lt", in, in, null,
                new ExpectedLogin(LocalTime.of(9, 0), ExpectedLogin.Source.PREFERRED), LocalTime.of(8, 40),
                new AttendanceSummary(21, 18, 2, 1), agentQuiet ? null : in, agentQuiet,
                List.of(new UpcomingStatus(TODAY.plusDays(3), USER_DN, "alice", "Scheduled Leave")));
    }

    private static TeamSummary team() {
        String carol = "cn=Carol Clark,ou=Users,dc=winllc,dc=com";
        AccountabilityMetrics metrics = new AccountabilityMetrics(
                new AccountedFor(TODAY, null, 3, 2, 1, 1, List.of(new UserRef(carol, "Carol Clark")), 1),
                List.of(new StatusMixEntry(StatusMixEntry.CHECKED_IN, "Checked in", 1),
                        new StatusMixEntry("TDY", "TDY", 1),
                        new StatusMixEntry(StatusMixEntry.UNACCOUNTED, "Unaccounted for", 1)),
                new AgentCoverage(3, 2, 1, 0, 7, List.of(new StoppedAgent(BOB, "Bob Barker", TODAY.minusDays(9)))));
        NotificationRecord notification = NotificationRecord.builder().id(42L).aboutUserDn(carol)
                .type(NotificationTypeEnum.ABSENT).notificationDate(ZonedDateTime.now()).build();
        return new TeamSummary(3, metrics, List.of(new NotInYet(carol, "Carol Clark", LocalTime.of(9, 0), true)),
                List.of(notification), List.of(new UpcomingStatus(TODAY.plusDays(2), BOB, "Bob Barker", "TDY")));
    }

    @Test
    void homeShowsTheSignedInUsersOwnFigures() throws Exception {
        when(homeService.forUser(eq(USER_DN), any())).thenReturn(new HomeDashboard(me(false), null));

        mockMvc.perform(get("/app/home").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(content().string(containsString("Checked in")))
                .andExpect(content().string(containsString("your preferred time")))
                .andExpect(content().string(containsString("18 of 21")))
                .andExpect(content().string(containsString("Scheduled Leave")))
                .andExpect(content().string(containsString("Reporting")))
                .andExpect(content().string(not(containsString("id=\"my-team\""))));
    }

    /** A plain user gets the overview, but not the sections for pages only admins and managers can open. */
    @Test
    void aPlainUsersHelpLeavesOutAdminAndManagerPages() throws Exception {
        when(homeService.forUser(eq(USER_DN), any())).thenReturn(new HomeDashboard(me(false), null));

        mockMvc.perform(get("/app/home").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"help-modal\"")))
                .andExpect(content().string(containsString("Finding your way around")))
                .andExpect(content().string(not(containsString("id=\"help-metrics\""))))
                .andExpect(content().string(not(containsString("id=\"help-settings\""))))
                .andExpect(content().string(not(containsString("id=\"help-date-picker\""))));
    }

    @Test
    void homeWarnsWhenTheUsersAgentIsQuiet() throws Exception {
        when(homeService.forUser(eq(USER_DN), any())).thenReturn(new HomeDashboard(me(true), null));

        mockMvc.perform(get("/app/home").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not reporting")))
                .andExpect(content().string(containsString("Nothing received in the last 30 days.")));
    }

    @Test
    void homeAddsTheTeamForAManager() throws Exception {
        when(homeService.forUser(eq(USER_DN), any())).thenReturn(new HomeDashboard(me(false), team()));

        mockMvc.perform(get("/app/home").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3 direct reports")))
                .andExpect(content().string(containsString("2 of 3 reports")))
                .andExpect(content().string(containsString("67%")))
                .andExpect(content().string(containsString("Unaccounted for")))
                .andExpect(content().string(containsString("expected 09:00")))
                .andExpect(content().string(containsString(">Late</span>")))
                .andExpect(content().string(containsString("href=\"/app/notifications/id/42\"")))
                .andExpect(content().string(containsString("Carol Clark \u00b7 ABSENT")))
                .andExpect(content().string(containsString("2 of 3 reported in the last 7 days")))
                .andExpect(content().string(containsString(">Bob Barker</a>")));
    }

    @Test
    void homeRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/app/home"))
                .andExpect(status().is4xxClientError());
    }

    /**
     * A plain USER only sees the parts of the tree their group permissions whitelist.
     */
    @Test
    void groupsRendersTheFilteredTreeForANonAdmin() throws Exception {
        TopLevelGroupProperties topLevel = new TopLevelGroupProperties();
        topLevel.setGroupsBaseDn(GROUPS_BASE_DN);

        when(properties.getGroups()).thenReturn(List.of(topLevel));
        when(properties.isGroupsInitiallyExpanded()).thenReturn(true);
        when(cacheService.getGroup(GROUPS_BASE_DN))
                .thenReturn(new LdapGroup(GROUPS_BASE_DN, "Groups"));
        when(permissionService.getUserGroupPermissions(any())).thenReturn(List.of());
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/groups").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("groups"))
                .andExpect(model().attributeExists("groups"))
                .andExpect(model().attribute("initiallyExpanded", true));
    }

    @Test
    void groupsExposesTheUsersFavouritesWhenARecordExists() throws Exception {
        TopLevelGroupProperties topLevel = new TopLevelGroupProperties();
        topLevel.setGroupsBaseDn(GROUPS_BASE_DN);

        UserRecord record = UserRecord.builder().dn(USER_DN).build();
        record.addGroup(GROUPS_BASE_DN);

        when(properties.getGroups()).thenReturn(List.of(topLevel));
        when(cacheService.getGroup(GROUPS_BASE_DN))
                .thenReturn(new LdapGroup(GROUPS_BASE_DN, "Groups"));
        when(permissionService.getUserGroupPermissions(any())).thenReturn(List.of());
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.of(record));

        mockMvc.perform(get("/app/groups").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("favoriteMap"));
    }

    /** The user's permissions are the same for every top-level group, so they are read once per page. */
    @Test
    void groupsReadsTheUsersPermissionsOnceHoweverManyTopLevelGroups() throws Exception {
        TopLevelGroupProperties groups = new TopLevelGroupProperties();
        groups.setGroupsBaseDn(GROUPS_BASE_DN);
        TopLevelGroupProperties companies = new TopLevelGroupProperties();
        companies.setGroupsBaseDn("ou=Companies,dc=winllc,dc=com");

        when(properties.getGroups()).thenReturn(List.of(groups, companies));
        when(cacheService.getGroup(anyString())).thenAnswer(inv -> new LdapGroup(inv.getArgument(0), "Top"));
        when(permissionService.getUserGroupPermissions(any())).thenReturn(List.of());
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/groups").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk());

        verify(permissionService, times(1)).getUserGroupPermissions(any());
    }

    /**
     * /app/users/{group} is gated by @PreAuthorize; a plain USER without a matching group
     * permission must not get through.
     */
    @Test
    void usersIsForbiddenForANonAdminWithoutGroupPermission() throws Exception {
        when(permissionEvaluator.groupCheck(anyString(), any())).thenReturn(false);

        mockMvc.perform(get("/app/users/some-group").with(x509(mockCert(USER_DN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersRendersWhenTheGroupCheckPasses() throws Exception {
        when(permissionEvaluator.groupCheck(anyString(), any())).thenReturn(true);
        when(cacheService.getGroup("some-group"))
                .thenReturn(new LdapGroup(GROUPS_BASE_DN, "Groups"));
        when(userRecordRepository.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/app/users/some-group").with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(view().name("users"))
                .andExpect(model().attribute("group", "Groups"))
                .andExpect(model().attribute("groupDn", GROUPS_BASE_DN))
                .andExpect(model().attribute("isFavorite", false));
    }

    @Test
    void groupsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/app/groups"))
                .andExpect(status().is4xxClientError());
    }
}
