package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.MetricsData;
import com.winllc.innoutwork.data.PieChartData;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final CheckInOutRecordRepository checkInOutRecordRepository;

    public MetricsService(CheckInOutRecordRepository checkInOutRecordRepository) {
        this.checkInOutRecordRepository = checkInOutRecordRepository;
    }


    public Map<CheckInOutEnum, Long> getTodaysStatistics(HttpSession session){
        ZonedDateTime beginning = CheckInOutService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        List<CheckInOutEnum> totalCurrentStatuses = checkInOutRecordRepository
                .findTotalCurrentStatuses(beginning, ending);

        Map<CheckInOutEnum, Long> counts = totalCurrentStatuses.stream()
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

        log.debug("Today's statuses for {}: {}", beginning.toLocalDate(), counts);

        return counts;
    }

    public MetricsData getCombinedStatistics(HttpSession session){
        long start = System.currentTimeMillis();

        MetricsData metricsData = new MetricsData();

        ZonedDateTime beginning = CheckInOutService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        List<CheckInOutRecord> totalCurrentRecords = checkInOutRecordRepository.findTotalCurrentRecords(beginning, ending);

        // The session can pin metrics to an earlier day, so log which day was actually used.
        log.debug("Building metrics for {} from {} current record(s)",
                beginning.toLocalDate(), totalCurrentRecords.size());

        Map<String, List<CheckInOutRecord>> orgGroup = totalCurrentRecords.stream()
                .filter(r -> r.getOrganization() != null)
                .collect(Collectors.groupingBy(CheckInOutRecord::getOrganization));

        Map<String, List<CheckInOutRecord>> employeeTypeGroup = totalCurrentRecords.stream()
                .filter(r -> r.getEmployeeType() != null)
                .collect(Collectors.groupingBy(CheckInOutRecord::getEmployeeType));

        Map<String, List<CheckInOutRecord>> locationGroup = totalCurrentRecords.stream()
                .filter(r -> r.getLocation() != null)
                .collect(Collectors.groupingBy(CheckInOutRecord::getLocation));

        Map<String, List<CheckInOutRecord>> branchGroup = totalCurrentRecords.stream()
                .filter(r -> r.getBranch() != null)
                .collect(Collectors.groupingBy(CheckInOutRecord::getBranch));

        Map<String, PieChartData<CheckInOutEnum>> orgGroupStats = new HashMap<>();
        Map<String, PieChartData<CheckInOutEnum>> employeeTypeGroupStats = new HashMap<>();
        Map<String, PieChartData<CheckInOutEnum>> locationGroupStats = new HashMap<>();
        Map<String, PieChartData<CheckInOutEnum>> branchGroupStats = new HashMap<>();

        orgGroup.forEach((key, value) -> {
            Map<CheckInOutEnum, Long> collect = value.stream()
                    .collect(Collectors.groupingBy(CheckInOutRecord::getAction, Collectors.counting()));

            PieChartData<CheckInOutEnum> orgPieChart = PieChartData.build(key, collect);
            orgGroupStats.put(key, orgPieChart);
        });

        employeeTypeGroup.forEach((key, value) -> {
            Map<CheckInOutEnum, Long> collect = value.stream()
                    .collect(Collectors.groupingBy(CheckInOutRecord::getAction, Collectors.counting()));
            PieChartData<CheckInOutEnum> orgPieChart = PieChartData.build(key, collect);
            employeeTypeGroupStats.put(key, orgPieChart);
        });

        locationGroup.forEach((key, value) -> {
            Map<CheckInOutEnum, Long> collect = value.stream()
                    .collect(Collectors.groupingBy(CheckInOutRecord::getAction, Collectors.counting()));
            PieChartData<CheckInOutEnum> orgPieChart = PieChartData.build(key, collect);
            locationGroupStats.put(key, orgPieChart);
        });

        branchGroup.forEach((key, value) -> {
            Map<CheckInOutEnum, Long> collect = value.stream()
                    .collect(Collectors.groupingBy(CheckInOutRecord::getAction, Collectors.counting()));
            PieChartData<CheckInOutEnum> orgPieChart = PieChartData.build(key, collect);
            branchGroupStats.put(key, orgPieChart);
        });


        Map<CheckInOutEnum, Long> allStats = totalCurrentRecords.stream()
                .collect(Collectors.groupingBy(s -> s.getAction(), Collectors.counting()));

        metricsData.setStatusCounts(allStats);
        metricsData.setOrgStatusCounts(orgGroupStats);
        metricsData.setEmployeeTypeStatusCounts(employeeTypeGroupStats);
        metricsData.setLocationStatusCounts(locationGroupStats);
        metricsData.setBranchStatusCounts(branchGroupStats);

        log.debug("Metrics built in {}ms: {} orgs, {} employee types, {} locations, {} branches",
                System.currentTimeMillis() - start, orgGroupStats.size(), employeeTypeGroupStats.size(),
                locationGroupStats.size(), branchGroupStats.size());

        return metricsData;
    }
}
