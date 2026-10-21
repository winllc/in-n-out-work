package com.winllc.innoutwork.api;

import com.winllc.innoutwork.audit.AuditEventRecord;
import com.winllc.innoutwork.audit.AuditEventRepository;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.ApiToken;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.ApiTokenRepository;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.support.FullApplicationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public API (/api/v1) end to end: tokens are made only by administrators and shown once, read only within
 * their scopes and their owner's rights as they are now, stop at expiry or revocation, are rate limited, are never
 * logged or kept in a session, and the API's chain takes nothing but tokens.
 */
@ExtendWith(OutputCaptureExtension.class)
class PublicApiTest extends FullApplicationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    @Autowired ApiTokenService tokens;
    @Autowired ApiTokenRepository tokenRepository;
    @Autowired UserRecordRepository people;
    @Autowired CheckInOutRecordRepository records;
    @Autowired UserEventRecordRepository events;
    @Autowired StatusRequestRepository requests;
    @Autowired AuditEventRepository audit;
    @Autowired JdbcTemplate jdbc;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void data() {
        records.deleteAll();
        events.deleteAll();
        requests.deleteAll();
        for (String dn : List.of(ADMIN, ALICE, BOB, JANE)) {
            UserRecord record = people.findByDnIgnoreCase(dn).orElseGet(UserRecord::new);
            record.setDn(dn);
            record.setUserRole(UserRoleEnum.USER);
            record.setLocation(dn.equals(JANE) ? "Los Angeles" : "New York");
            people.save(record);
            CheckInOutRecord in = new CheckInOutRecord();
            in.setDn(dn);
            in.setAction(CheckInOutEnum.CHECK_IN);
            in.setTimestamp(ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).plusHours(8));
            records.save(in);
        }
        events.save(UserEventRecord.builder().dn(BOB).date(today.plusDays(2)).status(UserStatusEnum.SCHEDULED_LEAVE).build());
        events.save(UserEventRecord.builder().dn(JANE).date(today.plusDays(3)).status(UserStatusEnum.WORK_FROM_HOME).build());
        requests.save(StatusRequest.builder().requesterDn(BOB).status(UserStatusEnum.SCHEDULED_LEAVE)
                .fromDate(today.plusDays(2)).toDate(today.plusDays(2)).state(RequestState.APPROVED)
                .createdAt(ZonedDateTime.now()).build());
    }

    private static UsernamePasswordAuthenticationToken signedIn(String dn, String... roles) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String r : roles) {
            authorities.add(new SimpleGrantedAuthority(r));
        }
        return UsernamePasswordAuthenticationToken.authenticated(dn, null, authorities);
    }

    private String adminToken(ApiScope... scopes) {
        return tokens.create("test", EnumSet.copyOf(List.of(scopes)), 30, 1000,
                signedIn(ADMIN, "ADMIN", "USER")).token();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> dns(JsonNode page) {
        List<String> dns = new ArrayList<>();
        page.get("items").forEach(i -> dns.add(i.get("dn").asString()));
        return dns;
    }

    @Test
    void anAdministratorsTokenReadsPeopleAttendanceAndAbsences() throws Exception {
        String token = adminToken(ApiScope.PEOPLE_READ, ApiScope.ATTENDANCE_READ, ApiScope.ABSENCES_READ);

        JsonNode people = getJson("/api/v1/people?size=50", token);
        assertTrue(dns(people).containsAll(List.of(ADMIN, ALICE, BOB, JANE)), people.toString());
        JsonNode bob = people.get("items").valueStream().filter(i -> i.get("dn").asString().equals(BOB)).findFirst().orElseThrow();
        assertEquals("IN", bob.get("status").asString());
        assertTrue(bob.get("details").asBoolean());
        assertNotNull(bob.get("checkedInAt").asString());

        JsonNode filtered = getJson("/api/v1/people?location=los angeles", token);
        assertEquals(List.of(JANE), dns(filtered));

        JsonNode attendance = getJson("/api/v1/attendance/events?from=" + today + "&to=" + today, token);
        assertEquals(4, attendance.get("totalItems").asInt(), attendance.toString());
        assertEquals("CHECK_IN", attendance.get("items").get(0).get("action").asString());

        JsonNode absences = getJson("/api/v1/absences?from=" + today + "&to=" + today.plusDays(7), token);
        assertEquals(List.of(BOB, JANE), dns(absences));
        assertEquals("self", absences.get("items").get(0).get("source").asString());

        JsonNode leave = getJson("/api/v1/absences/requests?from=" + today + "&to=" + today.plusDays(7)
                + "&state=approved", token);
        assertEquals(List.of(BOB), dns(leave));

        AuditEventRecord read = audit.findAll().stream().filter(e -> e.getAction().equals("data.exported")
                && e.getLine().contains("api.absences")).findFirst().orElseThrow();
        assertTrue(read.getLine().contains("\"type\":\"api-token\""), read.getLine());
    }

    @Test
    void eachEndpointNeedsItsScope() throws Exception {
        String token = adminToken(ApiScope.PEOPLE_READ);
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/events?from=" + today + "&to=" + today)
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/absences?from=" + today + "&to=" + today)
                .header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void aTokenWhoseOwnerIsNoLongerAnAdministratorIsRefused() throws Exception {
        // A token Alice was given while she was an administrator; she no longer is (she manages a group, which is not
        // enough): it is refused, not narrowed.
        String token = adminToken(ApiScope.PEOPLE_READ, ApiScope.ATTENDANCE_READ);
        ApiToken record = tokenRepository.findByTokenHash(com.winllc.innoutwork.audit.TokenSecrets.hash(token)).orElseThrow();
        record.setOwnerDn(ALICE);
        tokenRepository.save(record);
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("auth.login")
                && a.getLine().contains("no longer an administrator")));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}={1}")
    @org.junit.jupiter.params.provider.CsvSource({
            "userAccountControl, 514",                       // Active Directory: disabled
            "msDS-User-Account-Control-Computed, 16",        // Active Directory: locked out
            "accountExpires, 130000000000000000",            // Active Directory: expired (2012)
            "nsAccountLock, TRUE",                           // 389 Directory Server: disabled
            "pwdAccountLockedTime, 000001010000Z"})          // OpenLDAP ppolicy: locked until unlocked
    void aTokenWhoseOwnerIsDisabledLockedOrExpiredInTheDirectoryIsRefusedAtOnce(String attribute, String value)
            throws Exception {
        String token = adminToken(ApiScope.PEOPLE_READ);
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        try {
            setAttribute(ADMIN, attribute, value);
            mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        } finally {
            clearAccountStatus(ADMIN);
        }
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void aTokenWhoseOwnerLeftTheDirectoryIsRefused() throws Exception {
        String token = adminToken(ApiScope.PEOPLE_READ);
        ApiToken record = tokenRepository.findByTokenHash(com.winllc.innoutwork.audit.TokenSecrets.hash(token)).orElseThrow();
        record.setOwnerDn("cn=Gone Person,ou=Users,dc=winllc,dc=com");
        tokenRepository.save(record);
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedExpiredUnknownAndMalformedTokensAreRefusedAndAudited() throws Exception {
        String revoked = adminToken(ApiScope.PEOPLE_READ);
        ApiToken r = tokenRepository.findByTokenHash(com.winllc.innoutwork.audit.TokenSecrets.hash(revoked)).orElseThrow();
        tokens.revoke(r.getId(), signedIn(ADMIN, "ADMIN"));

        String expired = adminToken(ApiScope.PEOPLE_READ);
        ApiToken e = tokenRepository.findByTokenHash(com.winllc.innoutwork.audit.TokenSecrets.hash(expired)).orElseThrow();
        e.setExpiresAt(Instant.now().minusSeconds(1));
        tokenRepository.save(e);

        for (String bad : List.of(revoked, expired, ApiTokenService.PREFIX + "unknown", "not-a-token")) {
            mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")));
        }
        mockMvc.perform(get("/api/v1/people")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());

        List<String> refusals = audit.findAll().stream()
                .filter(a -> a.getAction().equals("auth.login") && a.getLine().contains("public-api-token"))
                .map(AuditEventRecord::getLine).toList();
        assertTrue(refusals.stream().anyMatch(l -> l.contains("revoked token")), refusals.toString());
        assertTrue(refusals.stream().anyMatch(l -> l.contains("expired token")), refusals.toString());
        assertTrue(refusals.stream().anyMatch(l -> l.contains("unknown token")), refusals.toString());
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("api.token-revoked")));
    }

    @Test
    void overItsRateLimitATokenGets429() throws Exception {
        String token = tokens.create("slow", EnumSet.of(ApiScope.PEOPLE_READ), 1, 2,
                signedIn(ADMIN, "ADMIN")).token();
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("access.denied")
                && a.getLine().contains("rate_limited")));
    }

    @Test
    void onlyAdministratorsCreateTokensAndOnlyWithinTheLimits() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                tokens.create("x", EnumSet.of(ApiScope.PEOPLE_READ), 30, 10, signedIn(ALICE, "USER", "MANAGER")));
        assertThrows(com.winllc.innoutwork.service.InvalidRequestException.class, () ->
                tokens.create("x", EnumSet.noneOf(ApiScope.class), 30, 10, signedIn(ADMIN, "ADMIN")));
        assertThrows(com.winllc.innoutwork.service.InvalidRequestException.class, () ->
                tokens.create("x", EnumSet.of(ApiScope.PEOPLE_READ), 10_000, 10, signedIn(ADMIN, "ADMIN")));
        assertThrows(com.winllc.innoutwork.service.InvalidRequestException.class, () ->
                tokens.create("x", EnumSet.of(ApiScope.PEOPLE_READ), 30, 1_000_000, signedIn(ADMIN, "ADMIN")));
    }

    @Test
    void theTokenIsShownOnceAndKeptOutOfTheSessionTheLogAndTheAuditTrail(CapturedOutput output) throws Exception {
        MvcResult created = mockMvc.perform(post("/app/admin/integrations/tokens")
                        .with(user(ADMIN).authorities(new SimpleGrantedAuthority("ADMIN"), new SimpleGrantedAuthority("USER")))
                        .with(csrf())
                        .param("name", "Payroll").param("scope", "people:read").param("days", "30")
                        .param("ratePerMinute", "60"))
                .andExpect(status().isOk()).andReturn();
        String token = (String) created.getModelAndView().getModel().get("newToken");
        assertNotNull(token);
        assertTrue(token.startsWith(ApiTokenService.PREFIX));
        assertTrue(created.getResponse().getContentAsString().contains(token), "shown in the response");
        assertNull(created.getFlashMap().get("newToken"), "never a flash attribute");
        if (created.getRequest().getSession(false) != null) {
            var session = created.getRequest().getSession(false);
            for (String name : java.util.Collections.list(session.getAttributeNames())) {
                assertFalse(String.valueOf(session.getAttribute(name)).contains(token), name);
            }
        }
        for (byte[] stored : jdbc.queryForList("select attribute_bytes from spring_session_attributes", byte[].class)) {
            assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains(token), "in a stored session");
        }

        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token + "x"))
                .andExpect(status().isUnauthorized());

        assertFalse(output.getAll().contains(token), "the token is in the log");
        assertFalse(output.getAll().contains(token.substring(ApiTokenService.PREFIX.length() + 4)), "part of it is in the log");
        assertTrue(audit.findAll().stream().noneMatch(a -> a.getLine().contains(token)), "in the audit trail");
        ApiToken stored = tokenRepository.findByTokenHash(com.winllc.innoutwork.audit.TokenSecrets.hash(token)).orElseThrow();
        assertEquals("people:read", stored.getScopes());
        assertEquals(ADMIN, stored.getOwnerDn());
        assertFalse(stored.getTokenHash().contains(token));
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("api.token-created")
                && a.getLine().contains("people:read")));
    }

    @Test
    void theApiTakesNoSessionsAndNoWrites() throws Exception {
        String token = adminToken(ApiScope.PEOPLE_READ);
        MvcResult result = mockMvc.perform(get("/api/v1/people").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertNull(result.getResponse().getHeader("Set-Cookie"), "no session cookie");
        assertNull(result.getRequest().getSession(false), "no session");

        // A signed-in user is not a token: even put straight into the security context (as MockMvc does; the
        // API's chain never reads a session), they hold no API_TOKEN authority.
        mockMvc.perform(get("/api/v1/people").with(user(ADMIN).authorities(new SimpleGrantedAuthority("ADMIN"))))
                .andExpect(status().isForbidden());
        // Writes are refused even with a token, CSRF exemption or not.
        mockMvc.perform(post("/api/v1/people").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        // The admin page keeps its CSRF protection.
        mockMvc.perform(post("/app/admin/integrations/tokens")
                        .with(user(ADMIN).authorities(new SimpleGrantedAuthority("ADMIN")))
                        .param("name", "x").param("scope", "people:read"))
                .andExpect(status().isForbidden());
        // And is for administrators.
        mockMvc.perform(post("/app/admin/integrations/tokens")
                        .with(user(ALICE).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .with(csrf()).param("name", "x").param("scope", "people:read"))
                .andExpect(status().isForbidden());
    }

    @Test
    void badParametersAreJsonErrors() throws Exception {
        String token = adminToken(ApiScope.ATTENDANCE_READ);
        mockMvc.perform(get("/api/v1/attendance/events?from=" + today + "&to=" + today.minusDays(1))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value("bad_request"));
        mockMvc.perform(get("/api/v1/attendance/events?from=" + today.minusYears(2) + "&to=" + today)
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/attendance/events?to=" + today)
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/attendance/events?from=" + today + "&to=" + today + "&size=100000")
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }
}
