package com.winllc.innoutwork.data;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.charts.PieChartDataSet;
import lombok.Data;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.winllc.innoutwork.constant.ChartColorMap.COLOR_MAP;

@Data
public class PieChartData<T> {
    private static final List<String> colorList = new LinkedList<>();

    static {
        colorList.add("red");
        colorList.add("yellow");
        colorList.add("pink");
        colorList.add("brown");

    }

    private List<String> labels = new LinkedList<>();
    private List<PieChartDataSet> datasets = new LinkedList<>();

    public static <T> PieChartData<T> build(String chartLabel, Map<T, Long> dataMap){
        long totalUsers = dataMap.values().stream().mapToInt(Long::intValue).sum();
        return build(chartLabel, totalUsers, dataMap);
    }

    public static <T> PieChartData<T> build(String chartLabel, long totalCount, Map<T, Long> dataMap){
        PieChartData<T> data = new PieChartData<>();

        PieChartDataSet dataSet = new PieChartDataSet(chartLabel);

        AtomicInteger index = new AtomicInteger(0);

        AtomicInteger totalWithAction = new AtomicInteger();
        dataMap.forEach((key, value) -> {
            data.getLabels().add(key.toString());
            dataSet.getData().add(value.intValue());

            String color = COLOR_MAP.getOrDefault(key.toString(), colorList.get(index.getAndIncrement()));

            dataSet.getBackgroundColor().add(color);

            totalWithAction.addAndGet(value.intValue());
        });

        long noActivity = totalCount - totalWithAction.get();

        if(noActivity > 0){
            data.getLabels().add("No Activity");
            dataSet.getData().add((int) noActivity);
            dataSet.getBackgroundColor().add("grey");
        }

        data.addDataSet(dataSet);

        return data;
    }

    public void addDataSet(PieChartDataSet dataSet){
        this.datasets.add(dataSet);
    }
}
