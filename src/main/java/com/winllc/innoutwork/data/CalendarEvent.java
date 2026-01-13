package com.winllc.innoutwork.data;

import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Data
public class CalendarEvent {
    private String id;
    private String title;
    private String start;
    private String end;
    private String backgroundColor;

    public CalendarEvent(LocalDate date, String title) {
        setId(date.format(DateTimeFormatter.ISO_LOCAL_DATE)+title.replace(" ", "").replace("-", ""));
        setTitle(title);
        setStart(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }
}
