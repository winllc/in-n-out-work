package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.MetricsData;
import com.winllc.innoutwork.data.NotificationResponse;
import com.winllc.innoutwork.data.PieChartData;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.data.reports.DayReport;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.data.reports.UserReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.OrgParseRuleRecord;
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
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.winllc.innoutwork.controller.ProfileControllerTest.mockCert;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke test for the Thymeleaf templates that no controller slice covers.
 *
 * The pages are built on Tabler components and share a single layout; a broken expression
 * or fragment name only shows up when a template is actually rendered, so a test-only
 * controller feeds each view a representative model and asserts it renders.
 */
@WebMvcTest
@Import({TemplateRenderingTest.TemplateController.class, TemplateRenderingTest.TestSecurityConfig.class})
class TemplateRenderingTest {

    private static final String USER_DN = "CN=alice,OU=Test";

    @Autowired
    private MockMvc mockMvc;

    private void assertRenders(String path, String expectedFragment) throws Exception {
        mockMvc.perform(get(path).with(x509(mockCert(USER_DN))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(expectedFragment)));
    }

    @Test
    void settingsRenders() throws Exception {
        assertRenders("/render/settings", "Organization Parse Rules");
    }

    @Test
    void userDetailsRenders() throws Exception {
        assertRenders("/render/userdetails", "Member Of");
    }

    @Test
    void groupReportRenders() throws Exception {
        assertRenders("/render/groupreport", "User Reports");
    }

    @Test
    void metricsRenders() throws Exception {
        assertRenders("/render/metrics", "card-header-tabs");
    }

    @Test
    void notificationRenders() throws Exception {
        assertRenders("/render/notification", "datagrid");
    }

    @Test
    void orgDetailsRenders() throws Exception {
        assertRenders("/render/orgdetails", "user-table");
    }

    @Test
    void orgChartRenders() throws Exception {
        assertRenders("/render/orgchart", "modal-user-table");
    }

    @Test
    void userSearchRenders() throws Exception {
        assertRenders("/render/usersearch", "user-table");
    }

    @Test
    void groupsRenders() throws Exception {
        assertRenders("/render/groups", "group-tree");
    }

    /** The hierarchy fragment includes itself, so check a child level actually renders. */
    @Test
    void groupsRendersNestedChildren() throws Exception {
        assertRenders("/render/groups", "groupA");
    }

    @Test
    void usersRenders() throws Exception {
        assertRenders("/render/users", "user-table");
    }

    @Test
    void profileRenders() throws Exception {
        assertRenders("/render/profile", "My Information");
    }

    @Test
    void errorRenders() throws Exception {
        assertRenders("/render/error", "The application encountered an error");
    }

    @Test
    void unauthorizedRenders() throws Exception {
        assertRenders("/render/unauthorized", "You are not authorized to view this page");
    }

    /** The layout supplies the shell for every page above; check its own furniture too. */
    @Test
    void layoutRendersTheTablerShell() throws Exception {
        assertRenders("/render/groups", "navbar-vertical");
        assertRenders("/render/groups", "/libs/tabler/tabler.min.css");
        assertRenders("/render/groups", "page-wrapper");
    }

    /**
     * Test-only controller: each mapping returns one of the real views with a model that
     * mirrors what the production controller supplies.
     */
    @Controller
    static class TemplateController {

        @GetMapping("/render/settings")
        ModelAndView settings() {
            OrgParseRuleRecord rule = new OrgParseRuleRecord();
            rule.setId(1L);
            rule.setOrgName("RYS");
            rule.setOrgParseRegex("([a-zA-Z]+\\d)(\\d+)([a-zA-Z]+)$");

            ModelAndView mav = new ModelAndView("settings");
            mav.addObject("userDn", USER_DN);
            mav.addObject("orgParseRules", List.of(rule));
            mav.addObject("orgParseRuleForm", new OrgParseRuleRecord());
            return mav;
        }

        @GetMapping("/render/userdetails")
        ModelAndView userDetails() {
            UserStatus user = UserStatus.builder()
                    .dn(USER_DN)
                    .status("IN")
                    .organization("ORG1")
                    .employeeType("FT")
                    .location("HQ")
                    .notes("some notes")
                    .memberOf(List.of(new LdapGroup("cn=groupA,ou=groups", "groupA")))
                    .role(UserRoleEnum.USER.name())
                    .build();

            ModelAndView mav = new ModelAndView("userdetails");
            mav.addObject("user", user);
            mav.addObject("roles", UserRoleEnum.getVisibleRoles());
            mav.addObject("isGroupManager", true);
            return mav;
        }

        @GetMapping("/render/groupreport")
        ModelAndView groupReport() {
            LocalDate today = LocalDate.now();

            // One day with no activity and one with a check-in/check-out pair, so both
            // branches of the inlined timestamp expressions are exercised.
            UserDayReport emptyDay = UserDayReport.build(today.minusDays(1), null, List.of());
            UserDayReport workedDay = UserDayReport.build(today, null, List.of(
                    CheckInOutRecord.builder()
                            .action(CheckInOutEnum.CHECK_IN)
                            .timestamp(ZonedDateTime.now().minusHours(8))
                            .build(),
                    CheckInOutRecord.builder()
                            .action(CheckInOutEnum.CHECK_OUT)
                            .timestamp(ZonedDateTime.now())
                            .build()));

            UserReport userReport = UserReport.createUserReport(
                    LdapUser.builder()
                            .dn(USER_DN)
                            .employeeType("FT")
                            .organization("ORG1")
                            .location("HQ")
                            .build(),
                    new ArrayList<>(List.of(emptyDay, workedDay)));

            GroupReport report = GroupReport.build(
                    new LdapGroup("cn=groupA,ou=groups", "groupA"), today.minusDays(1), today);
            report.getUserReports().add(userReport);

            DayReport dayReport = new DayReport(today);
            dayReport.addUserReport(userReport);
            report.getDayReports().add(dayReport);

            ModelAndView mav = new ModelAndView("groupReport");
            mav.addObject("report", report);
            return mav;
        }

        @GetMapping("/render/metrics")
        ModelAndView metrics() {
            MetricsData data = new MetricsData();
            data.setTotalUsers(2L);

            Map<CheckInOutEnum, Long> counts = Map.of(CheckInOutEnum.CHECK_IN, 2L);
            data.getOrgStatusCounts().put("ORG1", PieChartData.build("ORG1", counts));
            data.getEmployeeTypeStatusCounts().put("FT", PieChartData.build("FT", counts));
            data.getLocationStatusCounts().put("HQ", PieChartData.build("HQ", counts));
            data.getBranchStatusCounts().put("B1", PieChartData.build("B1", counts));

            String chartJson = "{\"labels\":[\"CHECK_IN\"],\"datasets\":[{\"data\":[2]}]}";

            ModelAndView mav = new ModelAndView("metrics");
            mav.addObject("data", data);
            mav.addObject("totalLoginChartData", chartJson);
            mav.addObject("loginByTimeChartData", chartJson);
            return mav;
        }

        @GetMapping("/render/notification")
        ModelAndView notification() {
            NotificationRecord record = NotificationRecord.builder()
                    .id(1L)
                    .forUserDn(USER_DN)
                    .aboutUserDn(USER_DN)
                    .notificationDate(ZonedDateTime.now())
                    .type(NotificationTypeEnum.ABSENT)
                    .build();

            NotificationResponse form = new NotificationResponse();
            form.setNotificationId(1L);
            form.setResponse(UserStatusEnum.STANDARD.name());

            ModelAndView mav = new ModelAndView("notification");
            mav.addObject("notification", record);
            mav.addObject("form", form);
            mav.addObject("notificationFor", USER_DN);
            mav.addObject("statuses", Stream.of(UserStatusEnum.values()).toList());
            return mav;
        }

        @GetMapping("/render/orgdetails")
        ModelAndView orgDetails() {
            ModelAndView mav = new ModelAndView("orgdetails");
            mav.addObject("orgName", "ORG1");
            return mav;
        }

        @GetMapping("/render/orgchart")
        ModelAndView orgChart() {
            return new ModelAndView("orgchart");
        }

        @GetMapping("/render/usersearch")
        ModelAndView userSearch() {
            return new ModelAndView("usersearch");
        }

        @GetMapping("/render/groups")
        ModelAndView groups() {
            LdapGroup parent = new LdapGroup("ou=groups", "Groups");
            LdapGroup child = new LdapGroup("cn=groupA,ou=groups", "groupA");
            child.setGroupSize(3);
            child.setFavorite(true);
            parent.addChild(child);

            ModelAndView mav = new ModelAndView("groups");
            mav.addObject("groups", List.of(parent));
            mav.addObject("initiallyExpanded", true);
            mav.addObject("favoriteMap", Map.of("cn=groupA,ou=groups", "groupA"));
            return mav;
        }

        @GetMapping("/render/users")
        ModelAndView users() {
            ModelAndView mav = new ModelAndView("users");
            mav.addObject("group", "groupA");
            mav.addObject("groupDn", "cn=groupA,ou=groups");
            mav.addObject("isFavorite", true);
            return mav;
        }

        @GetMapping("/render/error")
        ModelAndView error() {
            return new ModelAndView("error");
        }

        @GetMapping("/render/unauthorized")
        ModelAndView unauthorized() {
            return new ModelAndView("unauthorized");
        }

        @GetMapping("/render/profile")
        ModelAndView profile() {
            ModelAndView mav = new ModelAndView("profile");
            mav.addObject("form", new ProfileForm());
            mav.addObject("user", UserStatus.builder()
                    .dn(USER_DN)
                    .status("IN")
                    .organization("ORG1")
                    .employeeType("FT")
                    .location("HQ")
                    .build());
            mav.addObject("userDn", USER_DN);
            mav.addObject("userCn", "alice");
            mav.addObject("statuses", Stream.of(UserStatusEnum.values()).toList());
            mav.addObject("profileUpdateUrl", "https://example.test/helpdesk");
            return mav;
        }
    }

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)")
                            .userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        public UserDetailsService userDetailsService() {
            // ADMIN so the sec:authorize sections of the templates are rendered too.
            return username -> User.withUsername(username)
                    .password("")
                    .authorities(UserRoleEnum.ADMIN.name())
                    .build();
        }
    }
}
