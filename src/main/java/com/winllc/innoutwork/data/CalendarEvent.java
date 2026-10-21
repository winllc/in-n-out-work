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
    /**
     * Where a click on the event goes, which FullCalendar follows on its own. Left null by the
     * personal calendars, where an event is the viewer's own day and has nowhere to lead.
     */
    private String url;

    public CalendarEvent(LocalDate date, String title) {
        setId(date.format(DateTimeFormatter.ISO_LOCAL_DATE)+title.replace(" ", "").replace("-", ""));
        setTitle(title);
        setStart(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }
}
