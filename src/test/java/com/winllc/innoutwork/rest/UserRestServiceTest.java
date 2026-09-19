package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
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
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The user search API as the page calls it: over HTTP, through security, serialised by the real
 * message converters. The filter handed to LDAP is checked against a live directory in
 * {@code LdapServiceDirectoryTest}; here it is the request, the response shape and the hand-off
 * from search hit to status lookup.
 */
@WebMvcTest(UserRestService.class)
@Import({UserRestService.class, UserRestServiceTest.TestSecurityConfig.class})
class UserRestServiceTest {

    private static final String USER_DN = "CN=user1,OU=Users,DC=winllc,DC=com";
    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";

    /** Every page whose Tabulator table is filled with UserStatus rows. */
    private static final List<String> USER_STATUS_TABLES =
            List.of("usersearch", "users", "orgdetails", "orgchart", "orgchart_2", "myreports");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    LdapService ldapService;
    @MockitoBean
    UserRecordRepository userRecordRepository;
    @MockitoBean
    CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean
    ApplicationProperties properties;
    @MockitoBean
    UserService userService;

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

        /** A CN starting "nobody" gets no application role; everyone else is a plain USER. */
        @Bean
        public UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(username.startsWith("CN=nobody") ? "NONE" : UserRoleEnum.USER.name())
                    .build();
        }
    }

    @BeforeEach
    void setUp() {
        when(properties.getUserBaseDn()).thenReturn("dc=winllc,dc=com");
        // What ApplicationProperties supplies when nothing overrides it; the search filter is
        // built from this rather than a literal, so the stub has to stand in for it.
        when(properties.getUserLdapFilter()).thenReturn(ApplicationProperties.DEFAULT_USER_LDAP_FILTER);
        when(ldapService.search(anyString())).thenReturn(List.of(UserStatus.builder().dn(BOB).build()));
        when(userService.getUserStatus(anyString(), any()))
                .thenAnswer(inv -> UserStatus.builder().dn(inv.getArgument(0)).status("IN").build());
    }

    private String filterSentFor(String search) throws Exception {
        mockMvc.perform(get("/api/users/search").param("search", search).with(x509(cert(USER_DN))))
                .andExpect(status().isOk());

        ArgumentCaptor<String> filter = ArgumentCaptor.forClass(String.class);
        verify(ldapService).search(filter.capture());
        return filter.getValue();
    }

    private JsonNode searchJson() throws Exception {
        String body = mockMvc.perform(get("/api/users/search").param("search", "bob").with(x509(cert(USER_DN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    @Test
    void searchesByCommonNameContainingTheTerm() throws Exception {
        assertEquals("(&(objectclass=inetOrgPerson)(cn=*bob*))", filterSentFor("bob"));
    }

    @Test
    void theTermIsEscapedBeforeReachingTheFilter() throws Exception {
        String esc = String.valueOf((char) 92);
        assertEquals("(&(objectclass=inetOrgPerson)(cn=*a" + esc + "2a" + esc + "29" + esc + "28b*))",
                filterSentFor("a*)(b"));
    }

    @Test
    void anEmptyTermStillSendsAParenthesisedFilter() throws Exception {
        assertEquals("(objectclass=inetOrgPerson)", filterSentFor(""));
    }

    @Test
    void aConfiguredFilterReplacesTheDefaultOnItsOwn() throws Exception {
        when(properties.getUserLdapFilter()).thenReturn("(objectclass=posixAccount)");

        assertEquals("(objectclass=posixAccount)", filterSentFor(""));
    }

    @Test
    void aConfiguredFilterIsAndedWithTheSearchTerm() throws Exception {
        when(properties.getUserLdapFilter())
                .thenReturn("(&(objectclass=inetOrgPerson)(!(employeeType=CONTRACTOR)))");

        assertEquals("(&(&(objectclass=inetOrgPerson)(!(employeeType=CONTRACTOR)))(cn=*bob*))",
                filterSentFor("bob"));
    }

    /** usersearch.html reads a bare array, not a {data: [...]} wrapper. */
    @Test
    void theResponseIsABareArrayOfRows() throws Exception {
        JsonNode json = searchJson();

        assertTrue(json.isArray(), json.toString());
        assertEquals(1, json.size());
        assertEquals(BOB, json.get(0).get("dn").asString());
        assertEquals("Bob Barker", json.get(0).get("cn").asString());
        assertEquals("IN", json.get(0).get("status").asString());
    }

    /** The row the table renders is the status lookup for the hit's DN, not the bare hit. */
    @Test
    void eachHitIsEnrichedByItsDn() throws Exception {
        searchJson();

        verify(userService).getUserStatus(eq(BOB), any());
    }

    @Test
    void searchingRequiresAnApplicationRole() throws Exception {
        mockMvc.perform(get("/api/users/search").param("search", "bob").with(x509(cert("CN=nobody,OU=Users,DC=winllc,DC=com"))))
                .andExpect(status().isForbidden());

        verify(ldapService, never()).search(anyString());
    }

    @Test
    void searchingRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/search").param("search", "bob"))
                .andExpect(status().is4xxClientError());

        verify(ldapService, never()).search(anyString());
    }

    /**
     * Contract between the API and the pages: every column field a user table binds must be a
     * property of the serialised row, otherwise that column silently renders empty.
     */
    @Test
    void everyUserTableColumnIsPresentInTheSerialisedRow() throws Exception {
        UserStatus full = UserStatus.builder()
                .dn(BOB).status("OUT").notes("n").organization("o").employeeType("e").location("l")
                .checkedInAt(ZonedDateTime.now()).checkedOutAt(ZonedDateTime.now())
                .lastStatusChangeAt(ZonedDateTime.now())
                .build();
        when(userService.getUserStatus(anyString(), any())).thenReturn(full);

        JsonNode row = searchJson().get(0);

        for (String template : USER_STATUS_TABLES) {
            List<String> fields = columnFields(template);
            assertFalse(fields.isEmpty(), template + ".html has no Tabulator column fields");
            for (String field : fields) {
                assertTrue(row.has(field) && !row.get(field).isNull(),
                        template + ".html binds column '" + field + "' but the row JSON is " + row);
            }
        }
    }

    /** Dates are shown as sent, so they must arrive formatted rather than as epoch numbers. */
    @Test
    void datesAreSerialisedAsFormattedStrings() throws Exception {
        when(userService.getUserStatus(anyString(), any())).thenReturn(UserStatus.builder()
                .dn(BOB).lastStatusChangeAt(ZonedDateTime.parse("2026-09-10T08:30:00-04:00[America/New_York]"))
                .build());

        JsonNode changed = searchJson().get(0).get("lastStatusChangeAt");

        assertTrue(changed.isString(), changed.toString());
        assertTrue(changed.asString().startsWith("09/10/2026 08:30"), changed.asString());
    }

    @Test
    void managersForAUserWithNoAlternatesIsEmpty() throws Exception {
        when(userRecordRepository.findByDnIgnoreCase(BOB)).thenReturn(Optional.of(new UserRecord()));

        String body = mockMvc.perform(get("/api/users/managers/{dn}", BOB).with(x509(cert(USER_DN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("[]", body);
    }

    private static List<String> columnFields(String template) throws Exception {
        String html = new ClassPathResource("templates/" + template + ".html").getContentAsString(StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("field:\\s*\"([^\"]+)\"").matcher(html);
        List<String> fields = new ArrayList<>();
        while (m.find()) {
            fields.add(m.group(1));
        }
        return fields;
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
