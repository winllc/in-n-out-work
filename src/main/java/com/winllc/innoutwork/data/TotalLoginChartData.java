package com.winllc.innoutwork.data;

import com.winllc.innoutwork.data.charts.PieChartDataSet;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TotalLoginChartData {
    private List<String> labels = new ArrayList<>();
    private List<PieChartDataSet> datasets = new ArrayList<>();
}
