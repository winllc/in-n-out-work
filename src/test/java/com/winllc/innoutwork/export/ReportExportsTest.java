package com.winllc.innoutwork.export;

import com.winllc.innoutwork.audit.AuditEventRepository;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.data.reports.UserReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.ReportService;
import com.winllc.innoutwork.service.TimeZones;
import com.winllc.innoutwork.service.UserService;
import com.winllc.innoutwork.support.FullApplicationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSV and XLSX downloads end to end, and time zones in reports: who may download what, what the file holds for
 * them, each person's times in their own (or their site's) time zone, and the audit record.
 */
class ReportExportsTest extends FullApplicationTest {

    private static final String ENGINEERING = "cn=Engineering,ou=Groups,dc=winllc,dc=com";
    private static final String CAROL = ADMIN;

    @Autowired MockMvc mockMvc;
    @Autowired UserRecordRepository people;
    @Autowired CheckInOutRecordRepository records;
    @Autowired AuditEventRepository audit;
    @Autowired UserService userService;
    @Autowired TimeZones timeZones;
    @Autowired ReportService reportService;

    /** 13:30 UTC on a fixed day: 09:30 in New York, 06:30 in Los Angeles, 08:30 in Chicago. */
    private static final ZonedDateTime ARRIVAL = ZonedDateTime.of(2026, 10, 14, 13, 30, 0, 0, ZoneId.of("UTC"));
    private static final LocalDate DAY = ARRIVAL.toLocalDate();

    @BeforeEach
    void data() {
        records.deleteAll();
        for (String dn : List.of(ALICE, BOB, CAROL, JANE)) {
            UserRecord record = people.findByDnIgnoreCase(dn).orElseGet(UserRecord::new);
            record.setDn(dn);
            record.setUserRole(dn.equals(ALICE) ? UserRoleEnum.MANAGER : UserRoleEnum.USER);
            record.setTimeZone(null);
            record.setLocation(dn.equals(CAROL) ? "Chicago" : "New York");
            people.save(record);
            CheckInOutRecord in = new CheckInOutRecord();
            in.setDn(dn);
            in.setAction(CheckInOutEnum.CHECK_IN);
            in.setTimestamp(ARRIVAL);
            records.save(in);
            CheckInOutRecord out = new CheckInOutRecord();
            out.setDn(dn);
            out.setAction(CheckInOutEnum.CHECK_OUT);
            out.setTimestamp(ARRIVAL.plusHours(8));
            records.save(out);
        }
        UserRecord bob = people.findByDnIgnoreCase(BOB).orElseThrow();
        bob.setTimeZone("America/Los_Angeles");
        people.save(bob);
    }

    private static UsernamePasswordAuthenticationToken signedIn(String dn, String... roles) {
        return UsernamePasswordAuthenticationToken.authenticated(dn, null,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void aPersonsZoneIsTheirOwnThenTheirSitesThenTheApplications() {
        assertEquals(ZoneId.of("America/Los_Angeles"), timeZones.zoneFor(people.findByDnIgnoreCase(BOB).orElseThrow()));
        assertEquals(ZoneId.of("America/Chicago"), timeZones.zoneFor(people.findByDnIgnoreCase(CAROL).orElseThrow()));
        assertEquals(timeZones.applicationZone(), timeZones.zoneFor(people.findByDnIgnoreCase(ALICE).orElseThrow()));
        UserRecord bad = new UserRecord();
        bad.setTimeZone("Mars/Olympus");
        bad.setLocation("chicago");
        assertEquals(ZoneId.of("America/Chicago"), timeZones.zoneFor(bad), "an unknown zone is ignored");
    }

    @Test
    void theProfileSetsAValidTimeZoneAndAuditsIt() {
        ProfileForm form = new ProfileForm();
        form.setTimeZone("Europe/Berlin");
        userService.updateProfile(signedIn(JANE, "USER"), form);
        assertEquals("Europe/Berlin", people.findByDnIgnoreCase(JANE).orElseThrow().getTimeZone());
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("person.updated")
                && a.getLine().contains("Europe/Berlin")));
        form.setTimeZone("Nowhere/Special");
        assertThrows(InvalidRequestException.class, () -> userService.updateProfile(signedIn(JANE, "USER"), form));
        form.setTimeZone("");
        userService.updateProfile(signedIn(JANE, "USER"), form);
        assertEquals(null, people.findByDnIgnoreCase(JANE).orElseThrow().getTimeZone());
    }

    @Test
    void theGroupReportShowsEachPersonsTimesInTheirZone() {
        GroupReport report = reportService.generateGroupReport(new LdapDn(ENGINEERING),
                DAY.atStartOfDay(ZoneId.systemDefault()), DAY.atStartOfDay(ZoneId.systemDefault()));
        UserReport bob = report.getUserReports().stream().filter(r -> r.getUserDn().equalsIgnoreCase(BOB)).findFirst().orElseThrow();
        assertEquals("06:30", bob.getDayReports().getFirst().getCheckInTime().toLocalTime().toString());
        assertEquals(ZoneId.of("America/Los_Angeles"), bob.getDayReports().getFirst().getCheckInTime().getZone());
    }

    @Test
    void aManagerDownloadsTheirGroupsAttendanceAsCsvInEachPersonsZone() throws Exception {
        MvcResult result = mockMvc.perform(post("/app/export/group-report")
                        .with(user(ALICE).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .with(csrf())
                        .param("groupDn", ENGINEERING).param("fromDate", DAY.toString()).param("toDate", DAY.toString())
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".csv")))
                .andReturn();
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> lines = Arrays.asList(csv.substring(1).split("\r\n"));
        assertEquals(String.join(",", ReportExports.ATTENDANCE_COLUMNS), lines.getFirst());
        assertEquals(3, lines.size(), "Alice and Bob, one day each: " + csv);
        String bob = lines.stream().filter(l -> l.contains("Bob Barker")).findFirst().orElseThrow();
        assertTrue(bob.contains("," + DAY + ",") && bob.contains(",06:30,14:30,480,America/Los_Angeles"), bob);
        assertTrue(lines.stream().noneMatch(l -> l.contains("Carol") || l.contains("Jane")), "only the group");
        assertTrue(audit.findAll().stream().anyMatch(a -> a.getAction().equals("data.exported")
                && a.getLine().contains("\"format\":\"csv\"") && a.getLine().contains("Engineering")));

        // In the viewer's zone instead.
        String mine = mockMvc.perform(post("/app/export/group-report")
                        .with(user(ALICE).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .with(csrf()).param("groupDn", ENGINEERING).param("fromDate", DAY.toString())
                        .param("toDate", DAY.toString()).param("format", "csv").param("zones", "mine"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(mine.lines().filter(l -> l.contains("Bob Barker")).findFirst().orElseThrow()
                .endsWith(timeZones.applicationZone().getId()), mine);
    }

    @Test
    void xlsxDownloadsAreWorkbooks() throws Exception {
        byte[] body = mockMvc.perform(post("/app/export/group-report")
                        .with(user(ALICE).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .with(csrf()).param("groupDn", ENGINEERING).param("fromDate", DAY.toString())
                        .param("toDate", DAY.plusDays(1).toString()).param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andReturn().getResponse().getContentAsByteArray();
        String sheet = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(sheet != null && sheet.contains("Bob Barker") && sheet.contains("America/Los_Angeles"));
        assertEquals(5, sheet.split("<row ").length - 1, "a header and two days for two people");
    }

    @Test
    void onlyManagersOfTheGroupAndAdministratorsMayDownloadIt() throws Exception {
        mockMvc.perform(post("/app/export/group-report")
                        .with(user(BOB).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .with(csrf()).param("groupDn", ENGINEERING).param("fromDate", DAY.toString())
                        .param("toDate", DAY.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/app/export/group-report")
                        .with(user(ALICE).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .param("groupDn", ENGINEERING).param("fromDate", DAY.toString()).param("toDate", DAY.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/app/export/group-report")
                        .with(user(ALICE).authorities(new SimpleGrantedAuthority("USER"), new SimpleGrantedAuthority("MANAGER")))
                        .with(csrf()).param("groupDn", ENGINEERING).param("fromDate", DAY.toString())
                        .param("toDate", DAY.plusYears(2).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPeopleListShowsCoworkersAsTheVisibilitySettingsSay() throws Exception {
        // Bob (a plain user): himself in full, Carol Clark as a coworker (who she is, not when she came in).
        for (String dn : List.of(BOB, CAROL)) {
            CheckInOutRecord in = new CheckInOutRecord();
            in.setDn(dn);
            in.setAction(CheckInOutEnum.CHECK_IN);
            in.setTimestamp(ZonedDateTime.now().minusMinutes(5));
            records.save(in);
        }
        String csv = mockMvc.perform(post("/app/export/people")
                        .with(user(BOB).authorities(new SimpleGrantedAuthority("USER")))
                        .with(csrf()).param("search", "Bar").param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String bobRow = csv.lines().filter(l -> l.contains("Bob Barker")).findFirst().orElseThrow();
        assertTrue(bobRow.matches(".*,IN,\\d\\d:\\d\\d,.*"), "his own times: " + bobRow);

        String coworkers = mockMvc.perform(post("/app/export/people")
                        .with(user(BOB).authorities(new SimpleGrantedAuthority("USER")))
                        .with(csrf()).param("search", "Cla").param("format", "csv"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String carol = coworkers.lines().filter(l -> l.contains("Carol Clark")).findFirst().orElseThrow();
        assertFalse(carol.matches(".*,\\d\\d:\\d\\d,.*"), "no times for a coworker: " + carol);

        mockMvc.perform(post("/app/export/people")
                        .with(user(JANE).authorities(new SimpleGrantedAuthority("USER")))
                        .with(csrf()).param("group", ENGINEERING).param("format", "csv"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/app/export/people")
                        .with(user(BOB).authorities(new SimpleGrantedAuthority("USER")))
                        .with(csrf()).param("search", "a").param("format", "csv"))
                .andExpect(status().isBadRequest());
    }
}
