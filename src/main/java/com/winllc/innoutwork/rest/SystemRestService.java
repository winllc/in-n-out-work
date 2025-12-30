package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.SystemDateTimeForm;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@RequestMapping("/api/system")
@RestController
public class SystemRestService {

    private final DateTimeFormatter dtf = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    @PostMapping("/updateSystemTime")
    public void saveToSession(HttpSession session, @RequestBody SystemDateTimeForm form) {
        ZonedDateTime systemTime = ZonedDateTime.parse(form.getDateTime(), dtf);
        session.setAttribute("systemTime", systemTime);
    }
}
