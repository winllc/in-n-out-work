package com.winllc.innoutwork.data.charts;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LineChartDataSet {
    private String label;
    private List<Integer> data = new ArrayList<>();
    private boolean fill;
    private String borderColor;
    private Double tension = 0.1;
}
