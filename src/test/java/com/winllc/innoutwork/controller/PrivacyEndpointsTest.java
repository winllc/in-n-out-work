package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.PersonalDataService;
import com.winllc.innoutwork.service.RetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.winllc.innoutwork.support.TestCerts.cert;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Self-service export (audited) and an administrator's two-step erasure. */
@WebMvcTest
@Import({PrivacyController.class, AdminPrivacyController.class, PrivacyEndpointsTest.TestConfig.class})
class PrivacyEndpointsTest {

    private static final String ADMIN = "CN=admin,OU=Users,DC=winllc,DC=com";
    private static final String JANE = "CN=Jane Doe,OU=Users,DC=winllc,DC=com";

    static final List<AuditEntry> AUDITED = new ArrayList<>();

    @Autowired private MockMvc mockMvc;

    @MockitoBean PersonalDataService personalData;
    @MockitoBean RetentionService retention;
    @MockitoBean(name = "accessPolicy") AccessPolicy accessPolicy;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(username.startsWith("CN=admin")
                            ? new String[]{UserRoleEnum.USER.name(), UserRoleEnum.ADMIN.name()}
                            : new String[]{UserRoleEnum.USER.name(), UserRoleEnum.MANAGER.name()})
                    .build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }

        @Bean
        AuditLog auditLog() {
            return new AuditLog() {
                @Override public void record(AuditEntry entry) { AUDITED.add(entry); }
                @Override public void recordQuietly(AuditEntry entry) { AUDITED.add(entry); }
                @Override public <T> T inTransaction(Supplier<T> change) { return change.get(); }
                @Override public void requireAvailable() { }
            };
        }
    }

    @BeforeEach
    void setUp() {
        AUDITED.clear();
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("format", PersonalDataService.FORMAT);
        export.put("checkIns", List.of(Map.of("action", "CHECK_IN")));
        when(personalData.export(anyString())).thenAnswer(inv -> {
            Map<String, Object> copy = new LinkedHashMap<>(export);
            copy.put("subject", Map.of("dn", inv.getArgument(0)));
            return copy;
        });
        when(personalData.inventory(anyString())).thenReturn(new LinkedHashMap<>(Map.of("checkIns", 4L, "userRecord", 1L)));
        when(personalData.inDirectory(anyString())).thenReturn(true);
    }

    @Test
    void anyoneDownloadsTheirOwnDataAndTheDownloadIsAudited() throws Exception {
        mockMvc.perform(get("/app/privacy/export").with(x509(cert(JANE))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".json")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.format").value(PersonalDataService.FORMAT))
                .andExpect(jsonPath("$.subject.dn").value(JANE));

        AuditEntry entry = AUDITED.getLast();
        assertEquals("data.exported", entry.action());
        assertEquals("personal-data", entry.details().get("report"));
        assertEquals(JANE, entry.target().id());
        assertEquals("self", entry.details().get("requestedBy"));
        assertEquals(1, entry.details().get("checkIns"));
    }

    @Test
    void theMyDataPageSaysWhatIsKept() throws Exception {
        when(personalData.inventory(anyString())).thenReturn(new LinkedHashMap<>(Map.of("userRecord", 1L,
                "checkIns", 4L, "calendarStatuses", 0L, "statusRequests", 0L, "absenceAlerts", 0L,
                "monthlySummaries", 0L)));
        mockMvc.perform(get("/app/privacy").with(x509(cert(JANE))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("What ReadyRoom keeps about you")))
                .andExpect(content().string(containsString("/app/privacy/export")));
    }

    @Test
    void onlyAdministratorsReachThePrivacyAdministration() throws Exception {
        mockMvc.perform(get("/app/admin/privacy").with(x509(cert(JANE)))).andExpect(status().isForbidden());
        mockMvc.perform(get("/app/admin/privacy/export").param("dn", ADMIN).with(x509(cert(JANE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/app/admin/privacy/erase").param("dn", ADMIN).param("token", "x")
                .param("confirmName", "admin").with(x509(cert(JANE)))).andExpect(status().isForbidden());
        verify(personalData, never()).erase(anyString());
        verify(personalData, never()).export(anyString());
    }

    @Test
    void anAdministratorExportsSomeonesDataOnTheirBehalf() throws Exception {
        mockMvc.perform(get("/app/admin/privacy/export").param("dn", JANE).with(x509(cert(ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject.dn").value("CN=Jane Doe,OU=Users,DC=winllc,DC=com"));
        assertEquals(ADMIN, AUDITED.getLast().details().get("requestedBy"));
    }

    /** Step one: what is held, whether they are in the directory, and the form with its one-time token. */
    private String stepOne(MockHttpSession session) throws Exception {
        String page = mockMvc.perform(get("/app/admin/privacy").param("dn", JANE).session(session)
                        .with(x509(cert(ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"person-in-directory\"")))
                .andExpect(content().string(containsString("id=\"erase-form\"")))
                .andExpect(content().string(containsString("data-confirm=\"Erase all personal data of Jane Doe?")))
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("name=\"token\" value=\"([0-9a-f]+)\"").matcher(page);
        org.junit.jupiter.api.Assertions.assertTrue(token.find(), page);
        return token.group(1);
    }

    @Test
    void erasureNeedsTheTokenAndTheNameTypedAgainAndSaysWhenTheyAreStillInTheDirectory() throws Exception {
        when(personalData.erase(anyString())).thenReturn(new PersonalDataService.Erasure(JANE,
                new LinkedHashMap<>(Map.of("checkIns", 4L)), true));
        MockHttpSession session = new MockHttpSession();
        String token = stepOne(session);

        mockMvc.perform(post("/app/admin/privacy/erase").param("dn", JANE).param("token", "not-it")
                        .param("confirmName", "Jane Doe").session(session).with(x509(cert(ADMIN))))
                .andExpect(flash().attribute("error", containsString("start again")));
        verify(personalData, never()).erase(anyString());

        token = stepOne(session);
        mockMvc.perform(post("/app/admin/privacy/erase").param("dn", JANE).param("token", token)
                        .param("confirmName", "John Roe").session(session).with(x509(cert(ADMIN))))
                .andExpect(flash().attribute("error", containsString("does not match")));
        verify(personalData, never()).erase(anyString());

        token = stepOne(session);
        mockMvc.perform(post("/app/admin/privacy/erase").param("dn", JANE).param("token", token)
                        .param("confirmName", " jane doe ").session(session).with(x509(cert(ADMIN))))
                .andExpect(flash().attribute("message", containsString("Erased the personal data of Jane Doe")))
                .andExpect(flash().attribute("warning", containsString("still in the directory")));
        verify(personalData).erase("CN=Jane Doe,OU=Users,DC=winllc,DC=com");

        // The token was single use.
        mockMvc.perform(post("/app/admin/privacy/erase").param("dn", JANE).param("token", token)
                        .param("confirmName", "Jane Doe").session(session).with(x509(cert(ADMIN))))
                .andExpect(flash().attribute("error", containsString("start again")));
    }

    @Test
    void nobodyErasesThemselves() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/app/admin/privacy").param("dn", ADMIN).session(session).with(x509(cert(ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You cannot erase yourself")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("id=\"erase-form\""))));
        mockMvc.perform(post("/app/admin/privacy/erase").param("dn", ADMIN).param("token", "x")
                        .param("confirmName", "admin").session(session).with(x509(cert(ADMIN))))
                .andExpect(flash().attribute("error", containsString("start again")));
        verify(personalData, never()).erase(anyString());
    }
}
