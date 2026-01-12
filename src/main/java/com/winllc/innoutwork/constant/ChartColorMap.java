package com.winllc.innoutwork.constant;

import java.util.Map;

public class ChartColorMap {

    public static final Map<String, String> COLOR_MAP = Map.ofEntries(
            Map.entry(CheckInOutEnum.CHECK_IN.name(), "green"),
        Map.entry(CheckInOutEnum.CHECK_OUT.name(), "blue"),
        Map.entry(CheckInOutEnum.LOCK.name(), "purple"),
        Map.entry(CheckInOutEnum.UNLOCK.name(), "orange")
    );
}
