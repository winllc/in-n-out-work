package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.TrackedPeopleLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.data.jpa.test.autoconfigure.AutoConfigureDataJpa;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureJdbc;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whose status event a request records: the Windows client's calls over HTTP, through the application's own
 * security chain, stored for real (H2). Only the logoff may be identified by a session id, and only once, while
 * the session is fresh, and never against the signed-in user; the id goes back in the logon's answer and nowhere
 * else. Refused logoffs are limited per address; accepted ones never are.
 */
@WebMvcTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkinout;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "application.logoff.refused-per-minute=" + CheckInOutRestServiceTest.RATE_LIMIT
})
@AutoConfigureJdbc
@AutoConfigureDataJpa
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SecurityConfig.class, CheckInOutRestService.class, CheckInOutService.class, CheckInOutRestServiceTest.Beans.class})
class CheckInOutRestServiceTest {

    static final int RATE_LIMIT = 5;

    private static final String ALICE = "CN=Alice Adams,OU=Users,DC=winllc,DC=com";
    private static final String BOB = "CN=Bob Barker,OU=Users,DC=winllc,DC=com";
    private static final String ADMIN = "CN=admin,OU=Users,DC=winllc,DC=com";
    /** Each test's logoffs come from their own address, so the per-address limit is only met where it is tested. */
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private CheckInOutRecordRepository records;
    @Autowired private UserRecordRepository users;

    @Autowired private JdbcTemplate jdbc;

    @MockitoBean AppUserDetailsService appUsers;
    @MockitoBean BaseLdapPathContextSource contextSource;
    @MockitoBean SecurityAudit securityAudit;

    @Configuration
    @EnableWebSecurity
    @AutoConfigurationPackage(basePackages = "com.winllc.innoutwork")
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            return properties;
        }

        @Bean
        TrackedPeopleLimit trackedPeopleLimit() {
            return TrackedPeopleLimit.NONE;
        }
    }

    private String address;

    @BeforeEach
    void setUp() {
        records.deleteAll();
        users.deleteAll();
        address = "10.0.0." + ADDRESSES.incrementAndGet();
        // What AppUserDetailsService grants: USER to everyone in the directory, ADMIN to super-users.
        when(appUsers.loadUserByUsername(anyString())).thenAnswer(inv -> {
            String dn = inv.getArgument(0);
            return User.withUsername(dn).password("")
                    .authorities(dn.equals(ADMIN) ? new String[]{"USER", "ADMIN"} : new String[]{"USER"})
                    .build();
        });
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private static RequestPostProcessor as(String dn) {
        return x509(cert(dn));
    }

    /** The logoff task: no certificate, no ticket. */
    private RequestPostProcessor anonymous() {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    /** What the Windows client posts: {@code POST /api/check/<action>} with its account name and maybe a session id. */
    private ResultActions report(String action, RequestPostProcessor who, String sessionId) throws Exception {
        String body = sessionId == null
                ? "{\"windowsUserId\":\"someone\"}"
                : "{\"windowsUserId\":\"someone\",\"sessionId\":\"" + sessionId + "\"}";
        return mockMvc.perform(post("/api/check/" + action).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Signs in as {@code dn}, checks in, and returns the session id the answer carried. */
    private String logon(String dn) throws Exception {
        JsonNode answer = json(report("in", as(dn), null).andExpect(status().isOk()));
        assertTrue(answer.hasNonNull("sessionId"), "the logon's answer carries the session id: " + answer);
        return answer.get("sessionId").asString();
    }

    private List<CheckInOutRecord> stored(CheckInOutEnum action) {
        return records.findAll().stream().filter(r -> r.getAction() == action).toList();
    }

    // --- the logon's answer -------------------------------------------------------------------------------

    /** The Windows client reads the session id at the top level of the logon's answer and caches it. */
    @Test
    void theLogonsAnswerCarriesItsSessionIdAtTheTopLevel() throws Exception {
        JsonNode answer = json(report("in", as(ALICE), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dn").value(ALICE))
                .andExpect(jsonPath("$.action").value("CHECK_IN")));

        assertEquals(stored(CheckInOutEnum.CHECK_IN).getFirst().getSessionId(), answer.get("sessionId").asString());
    }

    /**
     * A certificate whose subject is not in the directory signs nobody in, and the client is told so with a 401: a
     * redirect to the login page would end in a 200 that the client, following it, takes for success.
     */
    @ParameterizedTest
    @ValueSource(strings = {"in", "lock", "unlock"})
    void aCallThatIsNotSignedInIsAnsweredUnauthorizedNotRedirected(String action) throws Exception {
        String stranger = "CN=Stranger,OU=Users,DC=winllc,DC=com";
        when(appUsers.loadUserByUsername(stranger)).thenThrow(new UsernameNotFoundException(stranger));

        report(action, as(stranger), null).andExpect(status().isUnauthorized());
        report(action, anonymous(), null).andExpect(status().isUnauthorized());

        assertTrue(records.findAll().isEmpty(), "nothing is recorded for a call nobody signed in to");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "unlock", "out"})
    void noOtherAnswerCarriesASessionId(String action) throws Exception {
        logon(ALICE);

        report(action, as(ALICE), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").doesNotExist());
    }

    // --- signed in: the sign-in decides whose event it is ---------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"in", "lock", "unlock"})
    void someoneElsesSessionIdInTheBodyIsIgnored(String action) throws Exception {
        String alices = logon(ALICE);

        report(action, as(BOB), alices).andExpect(status().isOk());

        CheckInOutRecord latest = records.findAll().stream()
                .max((a, b) -> a.getId().compareTo(b.getId())).orElseThrow();
        assertEquals(BOB, latest.getDn(), "recorded under the signed-in user, not the session's owner");
        assertEquals(1, records.findAll().stream().filter(r -> ALICE.equals(r.getDn())).count(),
                "nothing more was recorded for Alice");
    }

    @Test
    void aSignedInLogoffNeedsNoSession() throws Exception {
        report("out", as(BOB), null).andExpect(status().isOk()).andExpect(jsonPath("$.dn").value(BOB));

        assertEquals(BOB, stored(CheckInOutEnum.CHECK_OUT).getFirst().getDn());
    }

    @Test
    void aSignedInLogoffWithSomeoneElsesSessionIsRefused() throws Exception {
        String alices = logon(ALICE);

        report("out", as(BOB), alices).andExpect(status().isForbidden());

        assertTrue(stored(CheckInOutEnum.CHECK_OUT).isEmpty());
        // Refusing Bob does not use Alice's session up.
        report("out", anonymous(), alices).andExpect(status().isOk()).andExpect(jsonPath("$.dn").value(ALICE));
    }

    @Test
    void aSignedInLogoffWithItsOwnSessionUsesItUp() throws Exception {
        String alices = logon(ALICE);

        report("out", as(ALICE), alices).andExpect(status().isOk());

        report("out", anonymous(), alices).andExpect(status().isUnauthorized());
    }

    // --- the logoff that cannot sign in ---------------------------------------------------------------------

    @Test
    void anAnonymousLogoffWithAValidSessionWorksOnce() throws Exception {
        String alices = logon(ALICE);

        report("out", anonymous(), alices)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dn").value(ALICE))
                .andExpect(jsonPath("$.action").value("CHECK_OUT"));
        assertEquals(ALICE, stored(CheckInOutEnum.CHECK_OUT).getFirst().getDn());

        report("out", anonymous(), alices)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unknown_session"));
        assertEquals(1, stored(CheckInOutEnum.CHECK_OUT).size(), "the second logoff stored nothing");
    }

    /** Older than the default maximum age, a week. */
    @Test
    void anExpiredSessionIsRefused() throws Exception {
        records.save(CheckInOutRecord.builder().dn(ALICE).action(CheckInOutEnum.CHECK_IN)
                .timestamp(ZonedDateTime.now().minusHours(169)).sessionId("last-weeks-session").build());

        report("out", anonymous(), "last-weeks-session").andExpect(status().isUnauthorized());

        assertTrue(stored(CheckInOutEnum.CHECK_OUT).isEmpty());
    }

    /** A workstation left logged on over a weekend or a long shift still records its logoff. */
    @Test
    void aSessionYoungerThanTheMaximumAgeIsAccepted() throws Exception {
        records.save(CheckInOutRecord.builder().dn(ALICE).action(CheckInOutEnum.CHECK_IN)
                .timestamp(ZonedDateTime.now().minusHours(167)).sessionId("monday-weeks-session").build());

        report("out", anonymous(), "monday-weeks-session").andExpect(status().isOk());
    }

    /** Lock and unlock records carry ids too, but only a check-in's identifies a logoff. */
    @Test
    void aSessionIdThatIsNotACheckInsIsRefused() throws Exception {
        logon(ALICE);
        report("lock", as(ALICE), null).andExpect(status().isOk());
        String locks = stored(CheckInOutEnum.LOCK).getFirst().getSessionId();

        report("out", anonymous(), locks).andExpect(status().isUnauthorized());
    }

    @Test
    void anAnonymousLogoffWithoutAUsableSessionStoresNothing() throws Exception {
        report("out", anonymous(), null).andExpect(status().isUnauthorized());
        report("out", anonymous(), "made-up").andExpect(status().isUnauthorized());

        assertEquals(0, records.count(), "no record without a DN");
    }

    // --- the limit on refused logoffs ----------------------------------------------------------------------

    /** Behind an ingress or NAT every workstation shares one address, and end of day is a burst. */
    @Test
    void aBurstOfValidLogoffsFromOneAddressIsAllAccepted() throws Exception {
        List<String> sessions = new ArrayList<>();
        for (int i = 0; i < 3 * RATE_LIMIT; i++) {
            sessions.add(logon("CN=Worker " + i + ",OU=Users,DC=winllc,DC=com"));
        }

        for (String session : sessions) {
            report("out", anonymous(), session).andExpect(status().isOk());
        }

        assertEquals(3 * RATE_LIMIT, stored(CheckInOutEnum.CHECK_OUT).size());
        verify(securityAudit, never()).refusedClientSessionsLimited(any(), anyInt());
    }

    @Test
    void refusedLogoffsOverTheLimitGet429AndAreAuditedOnce() throws Exception {
        for (int i = 0; i < RATE_LIMIT; i++) {
            report("out", anonymous(), "guess-" + i).andExpect(status().isUnauthorized());
        }

        for (int i = 0; i < 3; i++) {
            report("out", anonymous(), "guess-again-" + i)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(jsonPath("$.error").value("rate_limited"));
        }

        // Each refusal within the limit is audited; past it, only the address going over.
        verify(securityAudit, times(RATE_LIMIT)).unknownClientSession(any());
        verify(securityAudit, times(1)).refusedClientSessionsLimited(any(), eq(RATE_LIMIT));

        // Another workstation is unaffected.
        address = "10.1.0.1";
        report("out", anonymous(), "guess-elsewhere").andExpect(status().isUnauthorized());
    }

    @Test
    void aValidLogoffFromALimitedAddressIsStillAccepted() throws Exception {
        String alices = logon(ALICE);
        for (int i = 0; i <= RATE_LIMIT; i++) {
            report("out", anonymous(), "guess-" + i);
        }
        report("out", anonymous(), "guess-again").andExpect(status().isTooManyRequests());

        report("out", anonymous(), alices).andExpect(status().isOk()).andExpect(jsonPath("$.dn").value(ALICE));
    }

    /** Every logoff without a sign-in looks its check-in up by session id, under a lock. */
    @Test
    void theSessionLookupIsIndexed() {
        Integer columns = jdbc.queryForObject("select count(*) from information_schema.index_columns"
                + " where lower(index_name) = 'ix_check_in_out_session_id' and lower(table_name) = 'check_in_out_records'"
                + " and lower(column_name) = 'session_id'", Integer.class);

        assertEquals(1, columns, "the index V1__baseline.sql creates, under the same name");
    }

    @Test
    void anUnknownActionIsABadRequest() throws Exception {
        report("sideways", as(ALICE), null).andExpect(status().isBadRequest());
        assertEquals(0, records.count());
    }

    // --- the day's records ----------------------------------------------------------------------------------

    @Test
    void theDaysRecordsAreForAdministratorsOnly() throws Exception {
        mockMvc.perform(get("/api/check/records").with(as(BOB))).andExpect(status().isForbidden());
    }

    @Test
    void theDaysRecordsNeverShowASessionId() throws Exception {
        logon(ALICE);

        JsonNode page = json(mockMvc.perform(get("/api/check/records").with(as(ADMIN))).andExpect(status().isOk()));

        JsonNode row = page.get("data").get(0);
        assertEquals(ALICE, row.get("dn").asString());
        assertFalse(row.has("sessionId"), "a record's JSON must not carry its session id: " + row);
    }

    static X509Certificate cert(String dn) {
        X509Certificate c = mock(X509Certificate.class);
        X500Principal p = new X500Principal(dn);
        when(c.getSubjectDN()).thenReturn(p);
        when(c.getSubjectX500Principal()).thenReturn(p);
        return c;
    }
}
