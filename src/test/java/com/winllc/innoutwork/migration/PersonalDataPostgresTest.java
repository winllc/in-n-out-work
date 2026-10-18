package com.winllc.innoutwork.migration;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.repository.AttendanceSummaryRepository;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.PermissionRecordRepository;
import com.winllc.innoutwork.repository.StatusRequestRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PersonalDataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A person's export and erasure on PostgreSQL: everything ReadyRoom holds about them is in the export and gone after
 * the erasure, other people's data stays, references to them are taken out, and the erasure is audited.
 */
class PersonalDataPostgresTest extends PostgresTestSupport {

    private static final String JANE = "cn=Jane Doe,ou=Users,dc=winllc,dc=com";
    private static final String MAX = "cn=Max Moe,ou=Users,dc=winllc,dc=com";
    private static final String GROUP = "cn=Engineering,ou=Groups,dc=winllc,dc=com";

    @Autowired private UserRecordRepository users;
    @Autowired private PermissionRecordRepository permissions;
    @Autowired private CheckInOutRecordRepository checkIns;
    @Autowired private UserEventRecordRepository events;
    @Autowired private StatusRequestRepository requests;
    @Autowired private NotificationRepository notifications;
    @Autowired private AttendanceSummaryRepository summaries;
    @Autowired private GroupRecordRepository groups;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private final List<AuditEntry> audited = new ArrayList<>();
    private LdapService ldap;
    private PersonalDataService service;

    @BeforeEach
    void seed() {
        clean();
        ldap = mock(LdapService.class);
        when(ldap.lookupUser(any(LdapDn.class))).thenReturn(Optional.of(LdapUser.builder().dn(JANE).build()));
        service = new PersonalDataService(users, permissions, checkIns, events, requests, notifications, summaries,
                groups, ldap);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        service.setAuditLog(new AuditLog() {
            @Override public void record(AuditEntry entry) { audited.add(entry); }
            @Override public void recordQuietly(AuditEntry entry) { audited.add(entry); }
            @Override public <T> T inTransaction(Supplier<T> change) { return tx.execute(s -> change.get()); }
            @Override public void requireAvailable() { }
        });

        for (String dn : List.of(JANE, MAX)) {
            long id = jdbc.queryForObject("INSERT INTO user_records (dn, notes, user_role, chosen_login_time,"
                    + " alternate_managers, alternate_managers_review_pending) VALUES (?, ?, 'USER', '08:30', ?, false)"
                    + " RETURNING id", Long.class, dn, "notes of " + dn, dn.equals(MAX) ? JANE.toUpperCase() : null);
            jdbc.update("INSERT INTO permission_records (user_id, group_dn) VALUES (?, ?)", id, GROUP);
            jdbc.update("INSERT INTO check_in_out_records (dn, \"timestamp\", action, session_id, forced)"
                    + " VALUES (?, now(), 'CHECK_IN', 'secret-session', false)", dn);
            jdbc.update("INSERT INTO user_event_records (dn, date, status) VALUES (?, current_date, 'LATE_ARRIVAL')", dn);
            jdbc.update("INSERT INTO status_requests (requester_dn, status, from_date, to_date, state, created_at, note)"
                    + " VALUES (?, 'SCHEDULED_LEAVE', current_date, current_date, 'REQUESTED', now(), 'wedding')", dn);
            long summary = jdbc.queryForObject("INSERT INTO attendance_summaries (dn, month, days_checked_in, minutes_in,"
                    + " updated_at) VALUES (?, date_trunc('month', current_date)::date, 3, 600, now()) RETURNING id",
                    Long.class, dn);
            jdbc.update("INSERT INTO attendance_summary_statuses (summary_id, status, days) VALUES (?, 'TDY', 2)", summary);
        }
        // An alert about Jane sent to Max, and one about Max sent to Jane.
        jdbc.update("INSERT INTO notification_records (about_user_dn, for_user_dn, notification_date, type, ignore)"
                + " VALUES (?, ?, now(), 'ABSENT', false), (?, ?, now(), 'ABSENT', false)", JANE, MAX, MAX, JANE);
        jdbc.update("INSERT INTO group_records (group_dn, alternate_managers) VALUES (?, ?)", GROUP, JANE + ";" + MAX);
    }

    @AfterEach
    void clean() {
        for (String table : List.of("user_event_records", "status_requests", "check_in_out_records",
                "notification_records", "attendance_summaries", "permission_records", "user_records", "group_records")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void theExportHoldsEverythingAboutThePersonAndNoCredential() {
        Map<String, Object> export = service.export(JANE.toUpperCase());

        assertEquals(PersonalDataService.FORMAT, export.get("format"));
        @SuppressWarnings("unchecked")
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        assertEquals("notes of " + JANE, profile.get("notes"));
        assertEquals("08:30", profile.get("preferredLoginTime"));
        assertEquals(List.of(GROUP), export.get("groupAccess"));
        assertEquals(List.of(MAX), export.get("delegateOf"));
        assertEquals(List.of(GROUP), export.get("groupManagerOf"));
        assertEquals(1, ((List<?>) export.get("checkIns")).size());
        assertEquals(1, ((List<?>) export.get("calendarStatuses")).size());
        assertEquals(1, ((List<?>) export.get("statusRequests")).size());
        assertEquals(1, ((List<?>) export.get("absenceAlertsAboutYou")).size());
        assertEquals(1, ((List<?>) export.get("monthlySummaries")).size());
        assertFalse(export.toString().contains("secret-session"), "the logoff session id is a credential");
        assertFalse(export.toString().contains("notes of " + MAX), "nothing of anyone else's");
        @SuppressWarnings("unchecked")
        Map<String, Object> checkIn = (Map<String, Object>) ((List<?>) export.get("checkIns")).getFirst();
        assertTrue(checkIn.get("timestamp").toString().matches("\\d{4}-\\d{2}-\\d{2}T.*[+-]\\d{2}:\\d{2}|.*Z"),
                () -> "ISO 8601 with an offset: " + checkIn.get("timestamp"));
    }

    @Test
    void theInventoryCountsWhatErasureWouldRemove() {
        Map<String, Long> counts = service.inventory(JANE);

        assertEquals(1L, counts.get("userRecord"));
        assertEquals(1L, counts.get("groupAccess"));
        assertEquals(1L, counts.get("checkIns"));
        assertEquals(1L, counts.get("calendarStatuses"));
        assertEquals(1L, counts.get("statusRequests"));
        assertEquals(2L, counts.get("absenceAlerts"));
        assertEquals(1L, counts.get("monthlySummaries"));
        assertEquals(1L, counts.get("delegateOf"));
        assertEquals(1L, counts.get("groupManagerOf"));
    }

    @Test
    void erasureRemovesThePersonsDataAndReferencesToThemAndKeepsEveryoneElses() {
        PersonalDataService.Erasure erasure = service.erase(JANE.toUpperCase());

        assertTrue(erasure.inDirectory());
        for (String table : List.of("user_records", "check_in_out_records", "user_event_records", "attendance_summaries")) {
            assertEquals(0, count("SELECT count(*) FROM " + table + " WHERE lower(dn) = lower(?)", JANE), table);
            assertEquals(1, count("SELECT count(*) FROM " + table + " WHERE lower(dn) = lower(?)", MAX), table);
        }
        assertEquals(0, count("SELECT count(*) FROM status_requests WHERE lower(requester_dn) = lower(?)", JANE));
        assertEquals(1, count("SELECT count(*) FROM status_requests WHERE lower(requester_dn) = lower(?)", MAX));
        assertEquals(0, count("SELECT count(*) FROM notification_records"), "alerts about her, and sent to her");
        assertEquals(1, count("SELECT count(*) FROM permission_records"), "only Max's group access is left");
        assertEquals(1, count("SELECT count(*) FROM attendance_summary_statuses"), "her summary's statuses went with it");
        assertNull(jdbc.queryForObject("SELECT alternate_managers FROM user_records WHERE dn = ?", String.class, MAX),
                "she is no longer Max's delegate");
        assertEquals(MAX, jdbc.queryForObject("SELECT alternate_managers FROM group_records", String.class));

        AuditEntry entry = audited.getLast();
        assertEquals("person.erased", entry.action());
        assertEquals(JANE.toUpperCase(), entry.target().id());
        assertEquals(1L, entry.details().get("checkIns"));
        assertEquals(2L, entry.details().get("absenceAlerts"));
        assertEquals(true, entry.details().get("inDirectory"));
        assertFalse(entry.details().toString().contains("notes of"), "no notes in the audit trail");
        assertFalse(entry.details().toString().contains("wedding"));
    }

    @Test
    void erasingSomeoneWithNothingStoredRemovesNothing() {
        when(ldap.lookupUser(any(LdapDn.class))).thenReturn(Optional.empty());

        PersonalDataService.Erasure erasure = service.erase("cn=Nobody,ou=Users,dc=winllc,dc=com");

        assertFalse(erasure.inDirectory());
        assertTrue(erasure.removed().values().stream().allMatch(v -> v == 0L), erasure.removed()::toString);
        assertEquals(2, count("SELECT count(*) FROM user_records"));
    }
}
