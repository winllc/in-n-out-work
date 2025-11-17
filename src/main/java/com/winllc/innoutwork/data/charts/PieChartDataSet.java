package com.winllc.innoutwork.data.charts;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PieChartDataSet {
    private String label;
    private List<Integer> data = new ArrayList<>();
    private List<String> backgroundColor = new ArrayList<>();
    private int hoverOffset = 4;
}
