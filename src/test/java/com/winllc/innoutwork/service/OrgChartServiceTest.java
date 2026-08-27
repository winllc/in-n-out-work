package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.OrgNode;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Since the refactor the service no longer builds the org chart - that lives in
 * {@link com.winllc.innoutwork.service.loader.LdapOrgLoader} behind a loading cache.
 * What is left here is decorating the cached tree with check-in statistics.
 */
@ExtendWith(MockitoExtension.class)
class OrgChartServiceTest {

    private static final String ORGANIZATION = "TOP";
    private static final String USER_BASE_DN = "ou=users,dc=example,dc=com";
    private static final String ORG_ATTRIBUTE = "dutySubOrganization";
    private static final String TYPE_ATTRIBUTE = "employeeType";

    @Mock
    private CheckInOutRecordRepository checkInOutRecordRepository;

    @Mock
    private LdapService ldapService;

    @Mock
    private LoadingCache<String, OrgNode> orgNodeCache;

    private ApplicationProperties props;
    private OrgChartService service;

    @BeforeEach
    void setUp() {
        props = new ApplicationProperties();
        props.setOrganizationName(ORGANIZATION);
        props.setUserBaseDn(USER_BASE_DN);
        props.setUserLdapDutySubOrganizationAttribute(ORG_ATTRIBUTE);
        props.setUserLdapEmployeeTypeAttribute(TYPE_ATTRIBUTE);

        service = new OrgChartService(checkInOutRecordRepository, ldapService, props, orgNodeCache);
    }

    /**
     * Directory totals are loaded once by LdapOrgLoader when the tree is cached; the
     * per-page-load path must not go back to LDAP.
     */
    @Test
    void loadStatisticsNeverQueriesTheDirectory() {
        OrgNode top = treeWith(child("RYS"));
        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);
        when(checkInOutRecordRepository
                .findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(eq("RYS"), any(), any()))
                .thenReturn(List.of());

        service.loadStatistics();

        verifyNoInteractions(ldapService);
    }

    @Test
    void loadStatisticsFetchesTheTreeForTheConfiguredOrganization() {
        OrgNode top = new OrgNode(ORGANIZATION);
        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);

        assertSame(top, service.loadStatistics(), "the cached tree is returned as-is");

        verify(orgNodeCache).get(ORGANIZATION);
    }

    /**
     * A user counts as present when their first record of the day is a check-in and their
     * last one is not a check-out; the totals come from the directory.
     */
    @Test
    void loadStatisticsCountsCheckedInUsersPerEmployeeType() {
        OrgNode top = treeWith(child("RYS"));
        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);

        when(checkInOutRecordRepository
                .findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(eq("RYS"), any(), any()))
                .thenReturn(List.of(
                        // present: checked in and never checked out
                        record("cn=alice", "CIV", CheckInOutEnum.CHECK_IN, 8),
                        // absent: checked in earlier, then out again
                        record("cn=bob", "MIL", CheckInOutEnum.CHECK_IN, 7),
                        record("cn=bob", "MIL", CheckInOutEnum.CHECK_OUT, 9)));

        OrgNode rys = service.loadStatistics().getChildren().get(0);

        assertEquals(Map.of("CIV", 1), rys.getData().getNodeMembersByEmployeeType());
    }

    /**
     * Stats are loaded for the whole subtree, then rolled up, so a parent reflects its
     * descendants without the REST layer doing a second pass.
     */
    @Test
    void loadStatisticsLoadsAndRollsUpNestedChildren() {
        OrgNode rys = child("RYS");
        OrgNode rys34 = new OrgNode("34");
        rys.getChildren().add(rys34);
        rys.buildFullName("");
        OrgNode top = treeWith(rys);

        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);

        // Directory totals are already on the cached tree; the service adds presence.
        rys.getData().setTotalMembersByEmployeeType(new java.util.HashMap<>(Map.of("CIV", 2)));
        rys34.getData().setTotalMembersByEmployeeType(new java.util.HashMap<>(Map.of("MIL", 3)));

        stubPresence("RYS", record("cn=alice", "CIV", CheckInOutEnum.CHECK_IN, 8));
        stubPresence("RYS34", record("cn=bob", "MIL", CheckInOutEnum.CHECK_IN, 8));

        service.loadStatistics();

        // The child was visited recursively...
        assertEquals(Map.of("MIL", 1), rys34.getData().getNodeMembersByEmployeeType());
        // ...and its counts rolled into the parent's full-tree view.
        assertEquals(Map.of("CIV", 1, "MIL", 1), rys.getData().getFullTreeNodeMembersByEmployeeType());
        assertEquals(Map.of("CIV", 2, "MIL", 3), rys.getData().getFullTreeTotalMembersByEmployeeType());
        // 2 present of 5 total across the subtree.
        assertEquals(40.0, rys.getData().getFullTreePresentPercentage(), 0.0001);
    }

    /**
     * One failing branch must not cost the rest of the chart.
     */
    @Test
    void loadStatisticsKeepsGoingWhenOneChildFails() {
        OrgNode broken = child("BAD");
        OrgNode healthy = child("RYS");
        OrgNode top = treeWith(broken, healthy);

        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);

        when(checkInOutRecordRepository
                .findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(eq("BAD"), any(), any()))
                .thenThrow(new RuntimeException("database unavailable"));
        stubPresence("RYS", record("cn=alice", "CIV", CheckInOutEnum.CHECK_IN, 8));

        assertDoesNotThrow(() -> service.loadStatistics());

        assertTrue(broken.getData().getNodeMembersByEmployeeType().isEmpty());
        assertEquals(Map.of("CIV", 1), healthy.getData().getNodeMembersByEmployeeType());
    }

    /**
     * fullName is what identifies an org in both the records table and the directory, so a
     * node without one is skipped rather than queried with a blank key.
     */
    @Test
    void loadStatisticsSkipsNodesWithoutAFullName() {
        OrgNode top = treeWith(new OrgNode("no-full-name"));
        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);

        service.loadStatistics();

        verifyNoInteractions(checkInOutRecordRepository, ldapService);
    }

    /**
     * The synthetic root carries no fullName of its own; only its children map to real orgs.
     */
    @Test
    void loadStatisticsDoesNotQueryStatsForTheSyntheticRoot() {
        OrgNode top = treeWith();
        when(orgNodeCache.get(ORGANIZATION)).thenReturn(top);

        assertSame(top, service.loadStatistics());

        verifyNoInteractions(checkInOutRecordRepository, ldapService);
    }

    /* ------------------------------------------------------------------ *
     * helpers
     * ------------------------------------------------------------------ */

    /** Mirrors the loader: a synthetic root with no fullName, holding fully named children. */
    private static OrgNode treeWith(OrgNode... children) {
        OrgNode top = new OrgNode(ORGANIZATION);
        top.getChildren().addAll(List.of(children));
        return top;
    }

    private static OrgNode child(String name) {
        OrgNode node = new OrgNode(name);
        node.buildFullName("");
        return node;
    }

    private static CheckInOutRecord record(String dn, String employeeType, CheckInOutEnum action, int hour) {
        return CheckInOutRecord.builder()
                .dn(dn)
                .employeeType(employeeType)
                .action(action)
                .timestamp(ZonedDateTime.now().withHour(hour).withMinute(0))
                .build();
    }

    private void stubPresence(String fullName, CheckInOutRecord record) {
        when(checkInOutRecordRepository
                .findByDutySubOrganizationEqualsIgnoreCaseAndTimestampBetween(eq(fullName), any(), any()))
                .thenReturn(List.of(record));
    }
}
