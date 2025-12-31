package com.winllc.innoutwork.data.charts;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

@Data
public class PieChartDataSet {
    private String label;
    private List<Integer> data = new LinkedList<>();
    private List<String> backgroundColor = new LinkedList<>();
    private int hoverOffset = 4;

    public PieChartDataSet(String label) {
        this.label = label;
    }

    public void addDataPoint(int value, String color) {
        this.data.add(value);
        this.backgroundColor.add(color);
    }
}
