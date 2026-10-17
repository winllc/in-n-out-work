package com.winllc.innoutwork.rest;

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
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
 * else.
 */
@WebMvcTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkinout;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "application.logoff.anonymous-requests-per-minute=" + CheckInOutRestServiceTest.RATE_LIMIT
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

    @MockitoBean AppUserDetailsService appUsers;
    @MockitoBean BaseLdapPathContextSource contextSource;

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

    @Test
    void anExpiredSessionIsRefused() throws Exception {
        records.save(CheckInOutRecord.builder().dn(ALICE).action(CheckInOutEnum.CHECK_IN)
                .timestamp(ZonedDateTime.now().minusHours(25)).sessionId("yesterdays-session").build());

        report("out", anonymous(), "yesterdays-session").andExpect(status().isUnauthorized());

        assertTrue(stored(CheckInOutEnum.CHECK_OUT).isEmpty());
    }

    @Test
    void aSessionYoungerThanTheMaximumAgeIsAccepted() throws Exception {
        records.save(CheckInOutRecord.builder().dn(ALICE).action(CheckInOutEnum.CHECK_IN)
                .timestamp(ZonedDateTime.now().minusHours(23)).sessionId("this-mornings-session").build());

        report("out", anonymous(), "this-mornings-session").andExpect(status().isOk());
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

    @Test
    void anonymousLogoffsAreLimitedPerAddress() throws Exception {
        for (int i = 0; i < RATE_LIMIT; i++) {
            report("out", anonymous(), "guess-" + i).andExpect(status().isUnauthorized());
        }

        report("out", anonymous(), "guess-again")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").value("rate_limited"));

        // Another workstation is unaffected.
        address = "10.1.0.1";
        report("out", anonymous(), "guess-elsewhere").andExpect(status().isUnauthorized());
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
