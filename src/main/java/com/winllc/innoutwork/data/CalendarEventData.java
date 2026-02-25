package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class CalendarEventData {
    private String date;
    private String fromDate;
    private String toDate;
    private String holiday;
    private String title;
}
