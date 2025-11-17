package com.winllc.innoutwork.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.LoginByTimeChartData;
import com.winllc.innoutwork.data.MetricsData;
import com.winllc.innoutwork.data.TotalLoginChartData;
import com.winllc.innoutwork.data.charts.LineChartDataSet;
import com.winllc.innoutwork.data.charts.PieChartDataSet;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.DatabaseService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/app/metrics")
public class MetricsController {

    private final CacheService cacheService;
    private final DatabaseService databaseService;
    private final ApplicationProperties properties;

    public MetricsController(ApplicationProperties properties,
                             CacheService cacheService, DatabaseService databaseService) {
        this.properties = properties;
        this.cacheService = cacheService;
        this.databaseService = databaseService;
    }

    @GetMapping
    public ModelAndView get() throws JsonProcessingException {
        ModelAndView mv = new ModelAndView("metrics");

        MetricsData data = new MetricsData();
        data.setTotalUsers(cacheService.getLdapCount(properties.getBaseDn()));

        TotalLoginChartData totalLoginChartData = getTotalLoginChartData();
        LoginByTimeChartData loginByTimeChart = getLoginByTimeChart();

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

    private TotalLoginChartData getTotalLoginChartData(){
        Map<CheckInOutEnum, String> colorMap = new HashMap<>();
        colorMap.put(CheckInOutEnum.CHECK_IN, "green");
        colorMap.put(CheckInOutEnum.CHECK_OUT, "purple");
        colorMap.put(CheckInOutEnum.LOCK, "yellow");
        colorMap.put(CheckInOutEnum.UNLOCK, "blue");

        TotalLoginChartData data = new TotalLoginChartData();

        Long totalUsers = cacheService.getLdapCount(properties.getBaseDn());
        Map<CheckInOutEnum, Long> todaysStatistics = databaseService.getTodaysStatistics();

        PieChartDataSet dataSet = new PieChartDataSet();
        dataSet.setLabel("Total Users");

        AtomicInteger totalWithAction = new AtomicInteger();
        todaysStatistics.forEach((key, value) -> {
            data.getLabels().add(key.toString());
            dataSet.getData().add(value.intValue());
            dataSet.getBackgroundColor().add(colorMap.get(key));

            totalWithAction.addAndGet(value.intValue());
        });

        Long noActivity = totalUsers - totalWithAction.get();

        data.getLabels().add("No Activity");
        dataSet.getData().add(noActivity.intValue());
        dataSet.getBackgroundColor().add("grey");

        data.getDatasets().add(dataSet);

        return data;
    }

    private LoginByTimeChartData getLoginByTimeChart(){
        Map<CheckInOutEnum, String> colorMap = new HashMap<>();
        colorMap.put(CheckInOutEnum.CHECK_IN, "green");
        colorMap.put(CheckInOutEnum.CHECK_OUT, "grey");
        colorMap.put(CheckInOutEnum.LOCK, "yellow");
        colorMap.put(CheckInOutEnum.UNLOCK, "blue");

        LoginByTimeChartData data = new LoginByTimeChartData();

        ZonedDateTime beginning = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        List<ZonedDateTime> buckets = generate15MinBuckets(beginning, ending);

        buckets.forEach(bucket -> {
            data.getLabels().add(bucket.toString());
        });


        List<CheckInOutRecord> todaysRecords = databaseService.findTodaysRecords();

        Map<CheckInOutEnum, List<ZonedDateTime>> recordMap = todaysRecords.stream()
                .sorted()
                .collect(Collectors.groupingBy(r -> r.getAction(),
                        Collectors.mapping(r -> r.getTimestamp(), Collectors.toList())));

        recordMap.forEach((key, value) -> {
            LineChartDataSet lineChartDataSet = new LineChartDataSet();

            Map<ZonedDateTime, Integer> collect = buckets.stream()
                    .collect(Collectors.toMap(bucket -> bucket, bucket -> 0));

            for(ZonedDateTime val : value){
                ZonedDateTime snapped = snapTo15Min(val);

                collect.merge(snapped, 1, Integer::sum);
            }

            lineChartDataSet.setLabel(key.toString());
            lineChartDataSet.setData(new ArrayList<>(collect.values()));
            lineChartDataSet.setBorderColor(colorMap.get(key));
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
        return ts.withMinute(snapped).withSecond(0).withNano(0);
    }
}
