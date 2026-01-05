package com.winllc.innoutwork.data;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import lombok.Data;
import lombok.ToString;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@ToString
public class MetricsData {
    private Map<CheckInOutEnum, Long> statusCounts = new HashMap<>();
    private Map<CheckInOutEnum, List<ZonedDateTime>> recordSeries = new HashMap<>();
    private Long totalUsers;
    private String totalLoginChartData;
    private Map<String, PieChartData<CheckInOutEnum>> employeeTypeStatusCounts = new HashMap<>();
    private Map<String, PieChartData<CheckInOutEnum>> orgStatusCounts = new HashMap<>();
    private Map<String, PieChartData<CheckInOutEnum>> locationStatusCounts = new HashMap<>();
    private Map<String, PieChartData<CheckInOutEnum>> branchStatusCounts = new HashMap<>();
}
