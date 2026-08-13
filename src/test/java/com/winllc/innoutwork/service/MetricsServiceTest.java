package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.MetricsData;
import com.winllc.innoutwork.data.PieChartData;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsServiceTest {

    @Mock
    private CheckInOutRecordRepository checkInOutRecordRepository;
    @Mock
    private HttpSession session;

    @InjectMocks
    private MetricsService metricsService;

    @Test
    void getTodaysStatisticsCountsEachStatus() {
        when(checkInOutRecordRepository.findTotalCurrentStatuses(any(), any()))
                .thenReturn(List.of(
                        CheckInOutEnum.CHECK_IN,
                        CheckInOutEnum.CHECK_IN,
                        CheckInOutEnum.CHECK_OUT));

        Map<CheckInOutEnum, Long> stats = metricsService.getTodaysStatistics(session);

        assertEquals(2L, stats.get(CheckInOutEnum.CHECK_IN));
        assertEquals(1L, stats.get(CheckInOutEnum.CHECK_OUT));
        assertNull(stats.get(CheckInOutEnum.LOCK));
    }

    /**
     * The combined view buckets the same records four ways (org, employee type, location,
     * branch) and also keeps an overall count by action.
     */
    @Test
    void getCombinedStatisticsGroupsRecordsByEveryDimension() {
        when(checkInOutRecordRepository.findTotalCurrentRecords(any(), any()))
                .thenReturn(List.of(
                        record(CheckInOutEnum.CHECK_IN, "WINLLC", "CIV", "HQ", "B1"),
                        record(CheckInOutEnum.CHECK_IN, "WINLLC", "MIL", "HQ", "B2"),
                        record(CheckInOutEnum.CHECK_OUT, "OTHER", "CIV", "REMOTE", "B1")));

        MetricsData data = metricsService.getCombinedStatistics(session);

        assertEquals(2L, data.getStatusCounts().get(CheckInOutEnum.CHECK_IN));
        assertEquals(1L, data.getStatusCounts().get(CheckInOutEnum.CHECK_OUT));

        assertEquals(Set.of("WINLLC", "OTHER"), data.getOrgStatusCounts().keySet());
        assertEquals(Set.of("CIV", "MIL"), data.getEmployeeTypeStatusCounts().keySet());
        assertEquals(Set.of("HQ", "REMOTE"), data.getLocationStatusCounts().keySet());
        assertEquals(Set.of("B1", "B2"), data.getBranchStatusCounts().keySet());

        // WINLLC saw two check-ins, so its pie has a single slice of size 2.
        PieChartData<CheckInOutEnum> winllc = data.getOrgStatusCounts().get("WINLLC");
        assertEquals(List.of(CheckInOutEnum.CHECK_IN.toString()), winllc.getLabels());
        assertEquals(List.of(2), winllc.getDatasets().get(0).getData());
    }

    /**
     * Records missing a dimension are dropped from that dimension's grouping rather than
     * bucketed under null.
     */
    @Test
    void getCombinedStatisticsSkipsRecordsWithNullDimensions() {
        when(checkInOutRecordRepository.findTotalCurrentRecords(any(), any()))
                .thenReturn(List.of(
                        record(CheckInOutEnum.CHECK_IN, null, null, null, null),
                        record(CheckInOutEnum.CHECK_IN, "WINLLC", "CIV", "HQ", "B1")));

        MetricsData data = metricsService.getCombinedStatistics(session);

        assertEquals(Set.of("WINLLC"), data.getOrgStatusCounts().keySet());
        assertEquals(Set.of("CIV"), data.getEmployeeTypeStatusCounts().keySet());
        // Both records still count toward the overall total.
        assertEquals(2L, data.getStatusCounts().get(CheckInOutEnum.CHECK_IN));
    }

    @Test
    void getCombinedStatisticsHandlesNoRecords() {
        when(checkInOutRecordRepository.findTotalCurrentRecords(any(), any()))
                .thenReturn(List.of());

        MetricsData data = metricsService.getCombinedStatistics(session);

        assertTrue(data.getStatusCounts().isEmpty());
        assertTrue(data.getOrgStatusCounts().isEmpty());
        assertTrue(data.getEmployeeTypeStatusCounts().isEmpty());
        assertTrue(data.getLocationStatusCounts().isEmpty());
        assertTrue(data.getBranchStatusCounts().isEmpty());
    }

    private static CheckInOutRecord record(CheckInOutEnum action, String org, String employeeType,
                                           String location, String branch) {
        CheckInOutRecord record = new CheckInOutRecord();
        record.setAction(action);
        record.setOrganization(org);
        record.setEmployeeType(employeeType);
        record.setLocation(location);
        record.setBranch(branch);
        record.setTimestamp(ZonedDateTime.now());
        return record;
    }
}
