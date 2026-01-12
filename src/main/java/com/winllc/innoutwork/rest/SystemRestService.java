package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.SystemDateTimeForm;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@RequestMapping("/api/system")
@RestController
public class SystemRestService {

    @PostMapping("/updateSystemTime")
    public void saveToSession(HttpSession session, @RequestBody SystemDateTimeForm form) {
        ZonedDateTime systemTime = ZonedDateTime.parse(form.getDateTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        session.setAttribute("systemTime", systemTime);
    }
}
