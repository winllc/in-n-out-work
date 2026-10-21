package com.winllc.innoutwork.controller;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.rest.UserEventRestService;
import com.winllc.innoutwork.rest.UserRestService;
import com.winllc.innoutwork.security.PermissionEvaluator;
import com.winllc.innoutwork.service.AccountabilityMetricsService;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.DirectReportsService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import com.winllc.innoutwork.service.loader.LdapUserLoader;
import com.winllc.innoutwork.support.InMemoryDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The My Reports page end to end: the controller, the real {@link DirectReportsService},
 * {@link UserService} and {@link LdapService} over a real directory, and the real template.
 * <p>
 * Every layer is covered on its own elsewhere; what this adds is the one path a manager actually
 * takes - open the page, then let its table call the API - so a break anywhere between the
 * reverse manager-id search and the rendered HTML shows up as a failing test rather than a
 * spinner. The database side is mocked empty, which is also the state a fresh install is in:
 * reports who have never used the app still have to appear.
 */
@WebMvcTest({UserController.class, UserRestService.class, UserEventRestService.class})
@Import({UserController.class, UserRestService.class, UserEventRestService.class,
        MyReportsPageDirectoryTest.RealServices.class, MyReportsPageDirectoryTest.TestSecurityConfig.class})
class MyReportsPageDirectoryTest {

    private static final String BASE = InMemoryDirectory.BASE_DN;
    /** Carries street=MGR-100 in the fixture, so Bob and Carol report to her. */
    private static final String ALICE = "cn=Alice Adams,ou=Users," + BASE;
    private static final String JANE = "cn=Doe\\, Jane,ou=Users," + BASE;

    private static InMemoryDirectory directory;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean(name = "permissionEvaluator")
    PermissionEvaluator permissionEvaluator;
    @MockitoBean
    UserRecordRepository userRecordRepository;
    @MockitoBean
    CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean
    UserEventRecordRepository userEventRecordRepository;
    @MockitoBean
    GlobalCalendarRecordRepository globalCalendarRecordRepository;
    @MockitoBean
    NotificationRepository notificationRepository;

    @BeforeAll
    static void startDirectory() {
        directory = InMemoryDirectory.start();
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    /** The production services, wired against the in-memory directory instead of mocks. */
    @Configuration
    static class RealServices {
        @Bean
        ApplicationProperties properties() {
            // Mirrors application.yml, as LdapServiceDirectoryTest does.
            ApplicationProperties props = new ApplicationProperties();
            props.setUserBaseDn(BASE);
            props.setUserLdapFilter(ApplicationProperties.DEFAULT_USER_LDAP_FILTER);
            props.setUserLdapEmployeeTypeAttribute("employeeType");
            props.setUserLdapOrganizationAttribute("o");
            props.setUserLdapLocationAttribute("l");
            props.setUserLdapManagerIdAttribute("title");
            props.setManagerLdapIdAttribute("street");
            props.setUserLdapDutySubOrganizationAttribute("departmentNumber");
            return props;
        }

        @Bean
        LdapService ldapService(ApplicationProperties properties) {
            return new LdapService(directory.ldapTemplate(), properties);
        }

        @Bean("ldapUserLoadingCache")
        LoadingCache<String, LdapUser> ldapUserLoadingCache(LdapService ldapService) {
            return Caffeine.newBuilder().maximumSize(5000).build(new LdapUserLoader(ldapService));
        }

        @Bean
        CheckInOutService checkInOutService(CheckInOutRecordRepository checkInOutRecordRepository,
                                            UserRecordRepository userRecordRepository) {
            return new CheckInOutService(checkInOutRecordRepository, userRecordRepository);
        }

        @Bean
        UserService userService(UserRecordRepository userRecordRepository, LdapService ldapService,
                                ApplicationProperties properties, LoadingCache<String, LdapUser> ldapUserLoadingCache,
                                CheckInOutService checkInOutService,
                                UserEventRecordRepository userEventRecordRepository) {
            return new UserService(userRecordRepository, ldapService, properties, ldapUserLoadingCache,
                    checkInOutService, userEventRecordRepository);
        }

        @Bean
        AccountabilityMetricsService accountabilityMetricsService(
                CheckInOutRecordRepository checkInOutRecordRepository,
                UserEventRecordRepository userEventRecordRepository,
                UserRecordRepository userRecordRepository,
                GlobalCalendarRecordRepository globalCalendarRecordRepository) {
            return new AccountabilityMetricsService(checkInOutRecordRepository, userEventRecordRepository,
                    userRecordRepository, globalCalendarRecordRepository);
        }

        @Bean
        DirectReportsService directReportsService(UserService userService,
                                                  AccountabilityMetricsService accountabilityMetricsService,
                                                  CheckInOutRecordRepository checkInOutRecordRepository,
                                                  UserEventRecordRepository userEventRecordRepository,
                                                  GlobalCalendarRecordRepository globalCalendarRecordRepository,
                                                  NotificationRepository notificationRepository) {
            return new DirectReportsService(userService, accountabilityMetricsService, checkInOutRecordRepository,
                    userEventRecordRepository, globalCalendarRecordRepository, notificationRepository);
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        /** Plain USER, the role a manager of a directory team may well be left with. */
        @Bean
        public UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(UserRoleEnum.USER.name()).build();
        }
    }

    private String pageFor(String callerDn) throws Exception {
        return mockMvc.perform(get("/app/user/reports").with(x509(cert(callerDn))))
                .andExpect(status().isOk())
                .andExpect(view().name("myreports"))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theManagersPageNamesEveryoneReportingToThem() throws Exception {
        String html = pageFor(ALICE);

        assertTrue(html.contains("Direct reports"), html);
        // Found by the reverse manager-id search, not by anything stored in the database.
        assertTrue(html.contains("Bob Barker"), "Bob is missing from the page");
        assertTrue(html.contains("Carol Clark"), "Carol is missing from the page");
        // Nobody else's row may appear: Jane reports to no one.
        assertTrue(!html.contains("Doe, Jane"), "someone outside the team is on the page");
    }

    /** The headline figures render rather than blowing up on an empty database. */
    @Test
    void theTeamMetricsRenderWithNothingRecordedYet() throws Exception {
        String html = pageFor(ALICE);

        assertTrue(html.contains("id=\"team-metrics\""), html);
        assertTrue(html.contains("id=\"team-daily\""), html);
        assertTrue(html.contains("Attendance by report, last 30 days"), html);
        assertTrue(html.contains("of 2 reports") || html.contains("Not a working day"), html);
    }

    /**
     * A status one of the reports set reaches the page, matched to the member by DN. The status is
     * stored lower-cased here on purpose: the directory's spelling is mixed case, and the two are
     * only brought together if the comparison ignores case all the way through.
     */
    @Test
    void aStatusOnTheTeamIsCountedOnThePage() throws Exception {
        UserEventRecord status = UserEventRecord.builder()
                .dn("cn=bob barker,ou=users," + BASE)
                .date(LocalDate.now())
                .status(UserStatusEnum.WORK_FROM_HOME)
                .build();
        // Today's mix and the 30-day history are read by different queries; both see the status.
        when(userEventRecordRepository.findByDate(any())).thenReturn(List.of(status));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(status));

        String html = pageFor(ALICE);

        assertTrue(html.contains("Work From Home"), "the status mix does not mention the status set");
    }

    /** Somebody with no reports gets the empty state, not an empty table that reads as a failure. */
    @Test
    void amanagerlessUserGetsTheEmptyState() throws Exception {
        String html = pageFor(JANE);

        assertTrue(html.contains("No direct reports"), html);
        assertTrue(html.contains("Doe, Jane"), "the empty state should name who was looked up");
    }

    /** The table on the page fills itself from this endpoint; the rows are the same team. */
    @Test
    void theTablesApiReturnsTheSameTeam() throws Exception {
        String body = mockMvc.perform(get("/api/users/reports").with(x509(cert(ALICE))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = JsonMapper.builder().build().readTree(body).get("data");

        assertEquals(2, rows.size(), body);
        assertEquals("Bob Barker", rows.get(0).get("cn").asString());
        assertEquals("Carol Clark", rows.get(1).get("cn").asString());
        // Backfilled from the directory entry, so a report who has never used the app is not blank.
        assertEquals("WinLLC", rows.get(0).get("organization").asString());
        assertEquals("PT", rows.get(0).get("employeeType").asString());
    }

    /** The calendar card is on the page, pointed at the caller's own team. */
    @Test
    void theTeamCalendarIsOnThePage() throws Exception {
        String html = pageFor(ALICE);

        assertTrue(html.contains("Team Calendar"), html);
        assertTrue(html.contains("id=\"combined-calendar\""), "the calendar is missing from the page");
        assertTrue(html.contains("data-events-url=\"/api/event/reports\""), "the calendar fetches from nowhere");
    }

    /** And what it fetches is this team's statuses, named after the people in the directory. */
    @Test
    void theTeamCalendarServesTheTeamsStatuses() throws Exception {
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(UserEventRecord.builder()
                        .dn("CN=BOB BARKER,OU=USERS," + BASE.toUpperCase())
                        .date(LocalDate.of(2026, 9, 10))
                        .status(UserStatusEnum.WORK_FROM_HOME)
                        .build()));

        String body = mockMvc.perform(get("/api/event/reports").with(x509(cert(ALICE)))
                        .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-30T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = JsonMapper.builder().build().readTree(body);

        assertEquals(1, rows.size(), body);
        assertEquals("Bob Barker: Work From Home", rows.get(0).get("title").asString());
        // The directory's spelling, whatever case the status was stored in.
        assertEquals("/app/user/details/cn=Bob Barker,ou=Users," + BASE, rows.get(0).get("url").asString());
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
