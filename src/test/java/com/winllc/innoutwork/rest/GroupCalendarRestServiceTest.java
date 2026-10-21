package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.security.PermissionEvaluator;
import com.winllc.innoutwork.service.LdapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;

/**
 * The combined group calendar behind the card on the group page: over HTTP, through method
 * security, serialised by the real message converters.
 * <p>
 * The controller is imported explicitly for the usual reason in this suite: the
 * {@code @SpringBootApplication} is not in a parent package of the tests, so a {@code @WebMvcTest}
 * slice registers no controllers on its own.
 */
@WebMvcTest(UserEventRestService.class)
@Import({UserEventRestService.class, GroupCalendarRestServiceTest.TestSecurityConfig.class})
class GroupCalendarRestServiceTest {

    private static final String GROUP_DN = "cn=groupA,ou=Groups,dc=winllc,dc=com";
    private static final String ADMIN_DN = "CN=admin,OU=Users,DC=winllc,DC=com";
    private static final String MEMBER_DN = "CN=user1,OU=Users,DC=winllc,DC=com";
    private static final String BOB = "CN=Bob Barker,OU=Users,DC=winllc,DC=com";
    private static final String ALICE = "CN=Alice Anders,OU=Users,DC=winllc,DC=com";

    private static final String FROM = "2026-09-01T00:00:00Z";
    private static final String TO = "2026-09-30T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    UserEventRecordRepository userEventRecordRepository;
    @MockitoBean
    CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean
    GlobalCalendarRecordRepository globalCalendarRecordRepository;
    @MockitoBean
    ApplicationProperties properties;
    @MockitoBean
    LdapService ldapService;
    // Named, because the @PreAuthorize expression resolves it as @permissionEvaluator by bean name.
    @MockitoBean(name = "permissionEvaluator")
    PermissionEvaluator permissionEvaluator;

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

        /** Only "CN=admin" carries the role that opens any group; everyone else is a plain USER. */
        @Bean
        public UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(username.startsWith("CN=admin") ? UserRoleEnum.ADMIN.name()
                                                                 : UserRoleEnum.USER.name())
                    .build();
        }
    }

    @BeforeEach
    void setUp() {
        when(properties.getCalendarStatusEventColor()).thenReturn("blue");
        when(properties.getCalendarAbsentStatusEventColor()).thenReturn("purple");
    }

    private JsonNode calendarFor(String callerDn) throws Exception {
        String body = mockMvc.perform(get("/api/event/group/{dn}", GROUP_DN)
                        .param("from", FROM).param("to", TO)
                        .with(x509(cert(callerDn))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    private static UserEventRecord event(String dn, LocalDate date, UserStatusEnum status) {
        return UserEventRecord.builder().dn(dn).date(date).status(status).build();
    }

    private static List<JsonNode> rows(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }

    @Test
    void everyMembersStatusLandsOnTheCalendarNamedAfterThem() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB, ALICE));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(
                        event(BOB, LocalDate.of(2026, 9, 10), UserStatusEnum.WORK_FROM_HOME),
                        event(ALICE, LocalDate.of(2026, 9, 11), UserStatusEnum.TDY)));

        List<JsonNode> rows = rows(calendarFor(ADMIN_DN));

        assertEquals(2, rows.size(), rows.toString());
        assertEquals("Bob Barker: Work From Home", rows.get(0).get("title").asString());
        assertEquals("2026-09-10", rows.get(0).get("start").asString());
        assertEquals("Alice Anders: TDY", rows.get(1).get("title").asString());
        assertEquals("2026-09-11", rows.get(1).get("start").asString());
    }

    /** Clicking an entry opens the member it belongs to; FullCalendar follows the url itself. */
    @Test
    void eachEntryLinksToTheMemberItBelongsTo() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(event(BOB.toLowerCase(), LocalDate.of(2026, 9, 10), UserStatusEnum.TDY)));

        // The directory's spelling of the DN, not the lower-cased one the status row carries.
        assertEquals("/app/user/details/" + BOB, rows(calendarFor(ADMIN_DN)).get(0).get("url").asString());
    }

    /** Excusable statuses keep the configured status colour; the rest get the absence colour. */
    @Test
    void anAbsenceIsColouredDifferentlyFromAPlannedStatus() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB, ALICE));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(
                        event(ALICE, LocalDate.of(2026, 9, 10), UserStatusEnum.WORK_FROM_HOME),
                        event(BOB, LocalDate.of(2026, 9, 10), UserStatusEnum.ABSENT_UNEXCUSED)));

        List<JsonNode> rows = rows(calendarFor(ADMIN_DN));

        assertEquals("Alice Anders: Work From Home", rows.get(0).get("title").asString());
        assertEquals("blue", rows.get(0).get("backgroundColor").asString());
        assertEquals("Bob Barker: Absent Unexcused", rows.get(1).get("title").asString());
        assertEquals("purple", rows.get(1).get("backgroundColor").asString());
    }

    @Test
    void aLateArrivalCarriesTheTimeItMustBeInBy() throws Exception {
        UserEventRecord late = event(BOB, LocalDate.of(2026, 9, 10), UserStatusEnum.LATE_ARRIVAL);
        late.setLoginByTime(LocalTime.of(10, 30));
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(late));

        assertEquals("Bob Barker: Late Arrival by 10:30", rows(calendarFor(ADMIN_DN)).get(0).get("title").asString());
    }

    /**
     * Regression guard. {@code CalendarEvent}'s constructor derives the id from the title, so two
     * namesakes with the same status on the same day collided, and FullCalendar treats events
     * sharing an id as one event - the second one would not be drawn.
     */
    @Test
    void namesakesSharingAStatusOnADayStillGetDistinctEvents() throws Exception {
        String otherBob = "CN=Bob Barker,OU=Contractors,DC=winllc,DC=com";
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB, otherBob));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(
                        event(BOB, LocalDate.of(2026, 9, 10), UserStatusEnum.TDY),
                        event(otherBob, LocalDate.of(2026, 9, 10), UserStatusEnum.TDY)));

        List<JsonNode> rows = rows(calendarFor(ADMIN_DN));
        Set<String> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.get("id").asString()));

        assertEquals(2, rows.size());
        assertEquals(2, ids.size(), "ids collided: " + ids);
        // Same label, different member: the link is what tells them apart.
        assertEquals(Set.of("/app/user/details/" + BOB, "/app/user/details/" + otherBob),
                Set.of(rows.get(0).get("url").asString(), rows.get(1).get("url").asString()));
    }

    /** One query for the whole membership, lower-cased because the repository compares lower(dn). */
    @Test
    void theWholeMembershipIsReadInOneQuery() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB, ALICE));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of());

        calendarFor(ADMIN_DN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> dns = ArgumentCaptor.forClass(Collection.class);
        verify(userEventRecordRepository).findByLowercaseDnInAndDateBetween(dns.capture(),
                eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)));

        assertEquals(Set.of(BOB.toLowerCase(), ALICE.toLowerCase()), new HashSet<>(dns.getValue()));
    }

    /** The group DN reaches the directory as the DN of the group, not as a user search. */
    @Test
    void membershipIsReadForTheGroupOnThePath() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of());

        calendarFor(ADMIN_DN);

        ArgumentCaptor<LdapDn> group = ArgumentCaptor.forClass(LdapDn.class);
        verify(ldapService).getGroupMembers(group.capture());
        assertEquals(GROUP_DN, group.getValue().dn());
    }

    /**
     * An empty group must not reach the query: {@code in ()} is not valid SQL, so the select would
     * fail and the card would show "could not be loaded" rather than an empty month.
     */
    @Test
    void anEmptyGroupNeverReachesTheQuery() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of());

        assertTrue(rows(calendarFor(ADMIN_DN)).isEmpty());
        verify(userEventRecordRepository, never()).findByLowercaseDnInAndDateBetween(any(), any(), any());
    }

    /** Guarded like the group page: a plain user with no claim on the group gets nothing. */
    @Test
    void aUserWithNoClaimOnTheGroupIsForbidden() throws Exception {
        when(permissionEvaluator.groupCheck(anyString(), any())).thenReturn(false);

        mockMvc.perform(get("/api/event/group/{dn}", GROUP_DN)
                        .param("from", FROM).param("to", TO)
                        .with(x509(cert(MEMBER_DN))))
                .andExpect(status().isForbidden());

        verify(ldapService, never()).getGroupMembers(any());
        verify(userEventRecordRepository, never()).findByLowercaseDnInAndDateBetween(any(), any(), any());
    }

    /** The same check that lets a member open the group page lets them see its calendar. */
    @Test
    void aUserPermittedOnTheGroupCanReadItsCalendar() throws Exception {
        when(permissionEvaluator.groupCheck(anyString(), any())).thenReturn(true);
        when(ldapService.getGroupMembers(any())).thenReturn(List.of(BOB));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(event(BOB, LocalDate.of(2026, 9, 10), UserStatusEnum.TDY)));

        assertEquals(1, rows(calendarFor(MEMBER_DN)).size());
        verify(permissionEvaluator).groupCheck(eq(GROUP_DN), any());
    }

    @Test
    void theCalendarRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/event/group/{dn}", GROUP_DN).param("from", FROM).param("to", TO))
                .andExpect(status().is4xxClientError());

        verify(ldapService, never()).getGroupMembers(any());
    }

    /** A member value the directory holds that is not a parsable DN still gets its entry. */
    @Test
    void aMemberValueThatIsNotADnStillGetsAnEntry() throws Exception {
        when(ldapService.getGroupMembers(any())).thenReturn(List.of("not a dn"));
        when(userEventRecordRepository.findByLowercaseDnInAndDateBetween(any(), any(), any()))
                .thenReturn(List.of(event("not a dn", LocalDate.of(2026, 9, 10), UserStatusEnum.TDY)));

        JsonNode row = rows(calendarFor(ADMIN_DN)).get(0);

        assertEquals("not a dn: TDY", row.get("title").asString());
        assertFalse(row.get("id").asString().isBlank());
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
