package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.LoginByTimeChartData;
import com.winllc.innoutwork.data.MetricsData;
import com.winllc.innoutwork.data.PieChartData;
import com.winllc.innoutwork.data.charts.LineChartDataSet;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.MetricsService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.winllc.innoutwork.constant.ChartColorMap.COLOR_MAP;
import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_TIME_FORMATTER;

@Controller
@RequestMapping("/app/metrics")
public class MetricsController {

    private final CacheService cacheService;
    private final CheckInOutService checkInOutService;
    private final ApplicationProperties properties;
    private final MetricsService metricsService;

    public MetricsController(ApplicationProperties properties,
                             CacheService cacheService, CheckInOutService checkInOutService, MetricsService metricsService) {
        this.properties = properties;
        this.cacheService = cacheService;
        this.checkInOutService = checkInOutService;
        this.metricsService = metricsService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority(T(com.winllc.innoutwork.constant.UserRoleEnum).ADMIN, " +
            "T(com.winllc.innoutwork.constant.UserRoleEnum).MANAGER)")
    public ModelAndView get(HttpSession session) {
        ModelAndView mv = new ModelAndView("metrics");

        MetricsData data = metricsService.getCombinedStatistics(session);
        data.setTotalUsers(cacheService.getLdapCount(properties.getUserBaseDn()));

        PieChartData<CheckInOutEnum> totalLoginChartData = getTotalLoginChartData(session);
        LoginByTimeChartData loginByTimeChart = getLoginByTimeChart(session);

        ObjectMapper objectMapper = new ObjectMapper();
        String jsonData = objectMapper.writeValueAsString(totalLoginChartData);
        String jsonData2 = objectMapper.writeValueAsString(loginByTimeChart);

        data.setTotalLoginChartData(jsonData);

        //data.setRecordSeries(recordMap);

        mv.addObject("data", data);
        mv.addObject("totalLoginChartData", jsonData);
        mv.addObject("loginByTimeChartData", jsonData2);

        return mv;
    }

    private PieChartData<CheckInOutEnum> getTotalLoginChartData(HttpSession session){

        Long totalUsers = cacheService.getLdapCount(properties.getUserBaseDn());
        Map<CheckInOutEnum, Long> todaysStatistics = metricsService.getTodaysStatistics(session);

        PieChartData<CheckInOutEnum> chartData = PieChartData.build("Total Users", totalUsers, todaysStatistics);

        return chartData;
    }

    private LoginByTimeChartData getLoginByTimeChart(HttpSession session){
        LoginByTimeChartData data = new LoginByTimeChartData();

        ZonedDateTime beginning = CheckInOutService.getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        List<ZonedDateTime> buckets = generate15MinBuckets(beginning, ending);

        buckets.forEach(bucket -> {
            data.getLabels().add(DATE_TIME_FORMATTER.format(bucket));
        });

        List<CheckInOutRecord> todaysRecords = checkInOutService.findRecords(session);

        Map<CheckInOutEnum, List<ZonedDateTime>> recordMap = todaysRecords.stream()
                .sorted()
                .collect(Collectors.groupingBy(r -> r.getAction(),
                        Collectors.mapping(r -> r.getZonedDateTimestamp(), Collectors.toList())));

        recordMap.forEach((key, value) -> {
            LineChartDataSet lineChartDataSet = new LineChartDataSet();

            Map<ZonedDateTime, Integer> collect = buckets.stream()
                    .collect(Collectors.toMap(bucket -> bucket, bucket -> 0));

            for(ZonedDateTime val : value){
                ZonedDateTime snapped = snapTo15Min(val);

               // collect.merge(snapped, 1, Integer::sum);
                Integer i = collect.get(snapped);
                collect.put(snapped, i == null ? 1 : ++i);
            }

            List<Integer> vals = collect.entrySet()
                    .stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .map(Map.Entry::getValue)
                    .toList();

            lineChartDataSet.setLabel(key.toString());
            lineChartDataSet.setData(vals);
            lineChartDataSet.setBorderColor(COLOR_MAP.get(key.toString()));
            data.getDatasets().add(lineChartDataSet);
        });

        return data;
    }


    public static List<ZonedDateTime> generate15MinBuckets(
            ZonedDateTime start, ZonedDateTime end) {

        List<ZonedDateTime> buckets = new ArrayList<>();

        ZonedDateTime cursor = snapTo15Min(start);

        while (!cursor.isAfter(end)) {
            buckets.add(cursor);
            cursor = cursor.plusMinutes(15);
        }

        return buckets;
    }

    private static ZonedDateTime snapTo15Min(ZonedDateTime ts) {
        int snapped = (ts.getMinute() / 15) * 15;
        return ts.withMinute(snapped).withSecond(0).withNano(0).withZoneSameInstant(ZoneId.systemDefault());
    }
}
