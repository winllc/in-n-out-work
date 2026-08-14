package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.data.SystemDateTimeForm;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@RequestMapping("/api/system")
@RestController
public class SystemRestService {

    private static final Logger log = LoggerFactory.getLogger(SystemRestService.class);

    @PostMapping("/updateSystemTime")
    public void saveToSession(HttpSession session, Authentication authentication, @RequestBody SystemDateTimeForm form) {
        ZonedDateTime systemTime = ZonedDateTime.parse(form.getDateTime(), DateTimeFormatter.ISO_ZONED_DATE_TIME);
        session.setAttribute("systemTime", systemTime);

        // Every page that reads "today" honours this session override, so a support
        // question about wrong-looking data usually starts here.
        log.info("{} pinned their session system time to {}",
                authentication != null ? authentication.getName() : "unknown", systemTime);
    }
}
