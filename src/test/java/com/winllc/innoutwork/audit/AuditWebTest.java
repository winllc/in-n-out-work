package com.winllc.innoutwork.audit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enssolutions.license.LicenseManager;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.controller.GroupController;
import com.winllc.innoutwork.controller.SettingsController;
import com.winllc.innoutwork.controller.advice.ExceptionController;
import com.winllc.innoutwork.controller.advice.GlobalModelAttributes;
import com.winllc.innoutwork.license.LicenseController;
import com.winllc.innoutwork.license.LicenseExceptionHandler;
import com.winllc.innoutwork.license.LicensingConfiguration;
import com.winllc.innoutwork.license.TestLicenses;
import com.winllc.innoutwork.license.TestLicensing;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail end to end through MVC and Spring Security, over H2: what gets recorded (sign-in, denials,
 * settings, license, exports), the admin page, and the pull API, including in an unlicensed installation.
 */
@WebMvcTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditweb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@AutoConfigureDataJpa
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditWebTest {

    static final String ADMIN_DN = "CN=Ada Admin,OU=Users,DC=winllc,DC=com";
    static final String BOB_DN = "CN=Bob,OU=Users,DC=winllc,DC=com";
    static final String PASSWORD = "S3cret-Passw0rd!";

    static final RequestPostProcessor ADMIN = user(ADMIN_DN).authorities(
            new SimpleGrantedAuthority(UserRoleEnum.ADMIN.name()), new SimpleGrantedAuthority(UserRoleEnum.USER.name()));
    static final RequestPostProcessor BOB = user(BOB_DN).authorities(new SimpleGrantedAuthority(UserRoleEnum.USER.name()));

    @Configuration
    @Import({AuditStoreSupport.Store.class, SecurityAudit.class, LicenseModeAudit.class, AuditController.class,
            AuditApiController.class, AuditApiSecurityConfig.class,
            LicensingConfiguration.class, TestLicensing.class, LicenseExceptionHandler.class, LicenseController.class,
            GlobalModelAttributes.class, ExceptionController.class, SettingsController.class, GroupController.class,
            Web.class})
    static class Config {
    }

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class Web {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/login", "/error").permitAll()
                            .anyRequest().authenticated())
                    .formLogin(form -> form.loginPage("/login").failureUrl("/login?error").permitAll());
            return http.build();
        }

        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername(ADMIN_DN).password("{noop}" + PASSWORD).authorities("ADMIN", "USER").build(),
                    User.withUsername(BOB_DN).password("{noop}" + PASSWORD).authorities("USER").build());
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired AuditEventRepository events;
    @Autowired AuditTokenService tokens;
    @Autowired LicenseManager licenses;
    @Autowired AccessDenials denials;
    @Autowired AuditService service;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean CacheService cacheService;
    @MockitoBean ReportService reportService;
    /** GroupController's @PreAuthorize asks it; the administrators here manage every group. */
    @MockitoBean(name = "accessPolicy") AccessPolicy accessPolicy;

    private ListAppender<ILoggingEvent> stdout;

    @BeforeEach
    void capture() {
        when(accessPolicy.canManageGroup(any(), anyString())).thenReturn(true);
        stdout = AuditStoreSupport.captureAuditLines();
    }

    @AfterEach
    void restore() {
        AuditStoreSupport.release(stdout);
        TestLicenses.installFile(licenses, TestLicenses.full());
        denials.flushExpired(java.time.Instant.now().plusSeconds(3600));
    }

    private List<AuditEventRecord> recorded(String action) {
        return events.findAll(Sort.by("seq")).stream().filter(e -> e.getAction().equals(action)).toList();
    }

    private AuditEventRecord last(String action) {
        List<AuditEventRecord> found = recorded(action);
        assertFalse(found.isEmpty(), "no " + action + " recorded");
        return found.getLast();
    }

    private String allLines() {
        return events.findAll().stream().map(AuditEventRecord::getLine).collect(Collectors.joining("\n"))
                + "\n" + String.join("\n", AuditStoreSupport.lines(stdout));
    }

    // --- coverage ---------------------------------------------------------------------------------------------

    @Test
    void aSignInIsRecordedWithItsMethodAndAFailureWithTheAttemptedName() throws Exception {
        mockMvc.perform(formLogin("/login").user(ADMIN_DN).password(PASSWORD))
                .andExpect(status().is3xxRedirection());
        AuditEventRecord ok = last("auth.login");
        assertEquals("success", ok.getOutcome());
        assertEquals("user", ok.getActorType());
        assertEquals(ADMIN_DN, ok.getActorId());
        assertEquals("Ada Admin", ok.getActorName());
        assertTrue(ok.getLine().contains("\"method\":\"ldap\""), ok.getLine());
        assertTrue(ok.getLine().contains("\"roles\":[\"ADMIN\",\"USER\"]"), ok.getLine());

        mockMvc.perform(formLogin("/login").user("jdoe").password("wrong-" + PASSWORD))
                .andExpect(status().is3xxRedirection());
        AuditEventRecord failed = last("auth.login");
        assertEquals("failure", failed.getOutcome());
        assertEquals("warning", failed.getSeverity());
        assertEquals("anonymous", failed.getActorType());
        assertEquals("jdoe", failed.getActorId());
        assertEquals("bad credentials", failed.getReason());
        assertEquals("127.0.0.1", failed.getSourceIp());

        assertFalse(allLines().contains(PASSWORD), "a password never reaches the store or stdout");
    }

    @Test
    void aRefusalIsRecordedOnceAndRepeatsAreCollapsed() throws Exception {
        long before = recorded("access.denied").size();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/app/settings").with(BOB)).andExpect(status().isForbidden());
        }
        List<AuditEventRecord> denied = recorded("access.denied");
        assertEquals(before + 1, denied.size(), "identical denials within a minute are one event");
        AuditEventRecord first = denied.getLast();
        assertEquals("denied", first.getOutcome());
        assertEquals(BOB_DN, first.getActorId());
        assertEquals("permission_denied", first.getReason());
        assertTrue(first.getLine().contains("\"method\":\"GET\",\"path\":\"/app/settings\""), first.getLine());
        assertTrue(first.getLine().contains("\"count\":1"));

        denials.flushExpired(java.time.Instant.now().plusSeconds(61));
        AuditEventRecord repeat = recorded("access.denied").getLast();
        assertTrue(repeat.getLine().contains("\"count\":2"), repeat.getLine());
        assertEquals(BOB_DN, repeat.getActorId(), "the repeat keeps who was refused");
    }

    @Test
    void licenseRefusalsAreRecordedWithTheirReasonCode() throws Exception {
        TestLicenses.installFile(licenses, TestLicenses.license().without(
                com.winllc.innoutwork.license.LicensedFeature.METRICS).sign());

        mockMvc.perform(post("/app/group/report/exported").with(ADMIN).param("groupDn", "cn=Sales"))
                .andExpect(status().isForbidden());

        AuditEventRecord denied = last("access.denied");
        assertEquals("feature_not_licensed", denied.getReason());
        assertTrue(denied.getLine().contains("\"feature\":\"metrics\""), denied.getLine());
    }

    @Test
    void aSettingsChangeIsRecordedWithOldAndNewValues() throws Exception {
        mockMvc.perform(post("/app/settings/orgparserules").with(ADMIN)
                        .param("orgName", "Engineering").param("orgParseRegex", "^ENG-.*"))
                .andExpect(status().is3xxRedirection());

        AuditEventRecord changed = last("settings.changed");
        assertEquals("configuration", changed.getCategory());
        assertEquals("notice", changed.getSeverity());
        assertEquals(ADMIN_DN, changed.getActorId());
        assertTrue(changed.getLine().contains("\"orgParseRegex.old\":null,\"orgParseRegex.new\":\"^ENG-.*\""),
                changed.getLine());
    }

    @Test
    void aLicenseInstallAndARejectionAreRecordedWithoutTheLicenseText() throws Exception {
        String lic = TestLicenses.license().id("lic_audit_test").revision(7).sign();
        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", lic))
                .andExpect(status().is3xxRedirection());

        AuditEventRecord installed = last("license.installed");
        assertEquals("license", installed.getCategory());
        assertEquals("notice", installed.getSeverity());
        assertTrue(installed.getLine().contains("\"licenseId\":\"lic_audit_test\""), installed.getLine());

        mockMvc.perform(post("/app/admin/license").with(ADMIN).param("text", "not a license"))
                .andExpect(status().is3xxRedirection());
        AuditEventRecord rejected = last("license.rejected");
        assertEquals("failure", rejected.getOutcome());

        String all = allLines();
        assertFalse(all.contains(lic.substring(lic.length() / 2, lic.length() / 2 + 40)), "no license text");
    }

    @Test
    void aChangeOfLicenseModeIsRecordedOnce() throws Exception {
        TestLicenses.removeFile(licenses);
        mockMvc.perform(get("/app/settings").with(ADMIN).accept(MediaType.TEXT_HTML));
        mockMvc.perform(get("/app/settings").with(ADMIN).accept(MediaType.TEXT_HTML));

        AuditEventRecord changed = last("license.mode-changed");
        assertTrue(changed.getLine().contains("\"mode.new\":\"UNLICENSED\""), changed.getLine());
        assertEquals(1, recorded("license.mode-changed").stream()
                .filter(e -> e.getLine().contains("\"mode.new\":\"UNLICENSED\"")).count());
    }

    @Test
    void aReportExportIsRecorded() throws Exception {
        mockMvc.perform(post("/app/group/report/exported").with(ADMIN)
                        .param("groupDn", "cn=Sales,ou=Groups,dc=winllc,dc=com")
                        .param("fromDate", "2026-10-01T00:00:00-04:00").param("format", "pdf"))
                .andExpect(status().isNoContent());

        AuditEventRecord exported = last("data.exported");
        assertEquals("data", exported.getCategory());
        assertEquals("info", exported.getSeverity());
        assertEquals("Sales", exported.getTargetName());
        assertTrue(exported.getLine().contains("\"report\":\"group\",\"format\":\"pdf\""), exported.getLine());
    }

    // --- the admin page ---------------------------------------------------------------------------------------

    @Test
    void theAuditPageFiltersAndPagesNewestFirst() throws Exception {
        for (int i = 0; i < 55; i++) {
            service.record(AuditEntry.of(AuditEntry.Category.SYSTEM, "job.completed")
                    .actor(AuditEntry.Actor.system()).detail("n", "page-test-" + i));
        }

        MvcResult first = mockMvc.perform(get("/app/admin/audit").with(ADMIN)
                        .param("action", "job.completed").param("q", "page-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("55 events")))
                .andExpect(content().string(containsString("Page 1 of 2")))
                .andExpect(content().string(containsString("id=\"audit-delivery\"")))
                .andExpect(content().string(containsString("id=\"tokens\"")))
                .andExpect(content().string(containsString("id=\"audit-retention\"")))
                .andReturn();
        long newest = events.findFirstByOrderBySeqDesc().orElseThrow().getSeq();
        String html = first.getResponse().getContentAsString();
        assertTrue(html.contains("audit-row-" + newest), "newest first");
        assertTrue(html.indexOf("audit-row-" + newest) < html.indexOf("audit-row-" + (newest - 1)));

        mockMvc.perform(get("/app/admin/audit").with(ADMIN)
                        .param("action", "job.completed").param("q", "page-test").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Page 2 of 2")))
                .andExpect(content().string(not(containsString("audit-row-" + newest + "\""))));

        mockMvc.perform(get("/app/admin/audit/events/{seq}", newest).with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("prevHash")))
                .andExpect(content().string(containsString(events.findById(newest).orElseThrow().getHash())));
    }

    @Test
    void theCsvExportCarriesTheFilteredEventsAndIsAudited() throws Exception {
        service.record(AuditEntry.of(AuditEntry.Category.CONFIGURATION, "settings.changed")
                .detail("setting", "csv-test, with \"quotes\""));

        MvcResult result = mockMvc.perform(get("/app/admin/audit/export").with(ADMIN)
                        .param("format", "csv").param("q", "csv-test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();
        String[] rows = result.getResponse().getContentAsString().split("\r\n");
        assertTrue(rows[0].startsWith("seq,id,time,category,action,outcome,severity,actor_type"));
        assertEquals(2, rows.length, String.join("\n", rows));
        assertTrue(rows[1].contains("settings.changed"));
        assertTrue(rows[1].contains(",\"{\"\"setting\"\":\"\"csv-test, with "), "RFC 4180 quoting: " + rows[1]);

        AuditEventRecord exported = last("audit.exported");
        assertTrue(exported.getLine().contains("\"format\":\"csv\",\"filter\":\"text=csv-test\",\"count\":1"),
                exported.getLine());

        mockMvc.perform(get("/app/admin/audit/export").with(ADMIN).param("format", "ndjson").param("q", "csv-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"schema\":\"ens-audit/1\"")));
    }

    @Test
    void verifyingIsAuditedAndReported() throws Exception {
        mockMvc.perform(post("/app/admin/audit/verify").with(ADMIN))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("notice", containsString("Intact")));
        AuditEventRecord verified = last("audit.verified");
        assertTrue(verified.getLine().contains("\"result\":\"intact\""));
    }

    @Test
    void nonAdministratorsAreRefusedEverywhere() throws Exception {
        mockMvc.perform(get("/app/admin/audit").with(BOB)).andExpect(status().isForbidden());
        mockMvc.perform(get("/app/admin/audit/export").with(BOB)).andExpect(status().isForbidden());
        mockMvc.perform(post("/app/admin/audit/verify").with(BOB)).andExpect(status().isForbidden());
        mockMvc.perform(post("/app/admin/audit/tokens").with(BOB).param("name", "x")).andExpect(status().isForbidden());
        assertTrue(tokens.list().stream().noneMatch(t -> t.getName().equals("x")));
        assertTrue(last("access.denied").getLine().contains("\"path\":\"/app/admin/audit"));
    }

    @Test
    void adminsCanOpenExportAndVerifyTheLogWithoutALicense() throws Exception {
        TestLicenses.removeFile(licenses);
        mockMvc.perform(get("/app/admin/audit").with(ADMIN)).andExpect(status().isOk());
        mockMvc.perform(get("/app/admin/audit/export").with(ADMIN)).andExpect(status().isOk());
        mockMvc.perform(post("/app/admin/audit/verify").with(ADMIN)).andExpect(status().is3xxRedirection());

        TestLicenses.installFile(licenses, TestLicenses.license().expired(400).sign());
        mockMvc.perform(post("/app/admin/audit/verify").with(ADMIN)).andExpect(status().is3xxRedirection());
    }

    // --- the pull API -----------------------------------------------------------------------------------------

    @Test
    void aTokenPullsEventsAfterACursorAndRevokingItShutsItOut() throws Exception {
        MvcResult created = mockMvc.perform(post("/app/admin/audit/tokens").with(ADMIN).param("name", "Splunk"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String token = (String) created.getFlashMap().get("newToken");
        assertNotNull(token);
        assertTrue(token.matches("ensat_[A-Za-z0-9_-]{43}"), token);
        AuditEventRecord tokenCreated = last("audit.token-created");
        assertFalse(allLines().contains(token), "the token is never recorded, only its prefix");
        assertTrue(tokenCreated.getLine().contains("\"tokenName\":\"Splunk\""));

        long head = events.findFirstByOrderBySeqDesc().orElseThrow().getSeq();
        long cursor = head - 1;
        MvcResult pulled = mockMvc.perform(get("/api/audit/events").param("after", String.valueOf(cursor))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn();
        String[] lines = pulled.getResponse().getContentAsString().split("\n");
        assertEquals(1, lines.length);
        assertEquals(events.findById(head).orElseThrow().getLine(), lines[0]);
        assertNull(pulled.getRequest().getSession(false), "token requests never get a session");
        assertEquals(head, recorded("audit.token-created").getLast().getSeq(), "the pull itself is not audited");

        mockMvc.perform(get("/api/audit/events").param("after", "0").param("limit", "2")
                        .header("Authorization", "Bearer " + token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].seq").value(1))
                .andExpect(jsonPath("$.events[1].seq").value(2))
                .andExpect(jsonPath("$.next").value(2));

        mockMvc.perform(get("/api/audit/events").param("limit", "1001").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // Still works with no license at all: the audit trail stays available.
        TestLicenses.removeFile(licenses);
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        TestLicenses.installFile(licenses, TestLicenses.full());

        long id = tokens.list().stream().filter(t -> t.getName().equals("Splunk")).findFirst().orElseThrow().getId();
        mockMvc.perform(post("/app/admin/audit/tokens/{id}/revoke", id).with(ADMIN))
                .andExpect(status().is3xxRedirection());
        assertNotNull(last("audit.token-revoked"));

        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        AuditEventRecord refused = last("auth.login");
        assertEquals("failure", refused.getOutcome());
        assertTrue(refused.getLine().contains("\"method\":\"api-token\""));
        assertFalse(allLines().contains(token));
    }

    @Test
    void thePullApiRefusesMissingAndWrongTokensAndSessions() throws Exception {
        mockMvc.perform(get("/api/audit/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer ensat_nope"))
                .andExpect(status().isUnauthorized());
        // A signed-in administrator's session is not a token.
        mockMvc.perform(get("/api/audit/events").with(ADMIN)).andExpect(status().isUnauthorized());
    }
}
