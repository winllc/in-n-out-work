package com.winllc.innoutwork.data;

import com.winllc.innoutwork.data.charts.LineChartDataSet;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LoginByTimeChartData {
    private List<String> labels = new ArrayList<>();
    private List<LineChartDataSet> datasets = new ArrayList<>();
}
