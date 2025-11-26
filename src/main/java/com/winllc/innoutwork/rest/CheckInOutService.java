package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.CheckInOut;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.service.DatabaseService;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/check")
public class CheckInOutService {

    private static final Logger log = LoggerFactory.getLogger(CheckInOutService.class);

    private final DatabaseService databaseService;

    public CheckInOutService(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @PostMapping("/{action}")
    public CheckInOutRecord checkIn(Authentication auth,
                        @PathVariable String action,
                        @RequestBody CheckInOut checkInOut) {
        log.info("Status change {} request received: {}, for user: {}", action, checkInOut, auth != null ? auth.getName() : "NONE");

        if(StringUtils.isEmpty(action)) {
            throw new RuntimeException("action is empty");
        }else{
            Optional<CheckInOutRecord> optionalRecord = databaseService.lookupBySessionId(checkInOut.getSessionId());

            CheckInOutRecord record;

            String dn = auth != null ? auth.getName().replace(", ", ",") : null;

            if (optionalRecord.isPresent()) {
                dn = optionalRecord.get().getDn().replace(", ", ",");
            }

            if(action.equals("in")) {
                record = CheckInOutRecord.builder()
                        .dn(dn)
                        .windowsUserId(checkInOut.getWindowsUserId())
                        .timestamp(ZonedDateTime.now())
                        .action(CheckInOutEnum.CHECK_IN)
                        .build();
            }else if(action.equals("lock")) {
                record = CheckInOutRecord.builder()
                        .dn(dn)
                        .windowsUserId(checkInOut.getWindowsUserId())
                        .timestamp(ZonedDateTime.now())
                        .action(CheckInOutEnum.LOCK)
                        .build();
            }else if(action.equals("unlock")) {
                record = CheckInOutRecord.builder()
                        .dn(dn)
                        .windowsUserId(checkInOut.getWindowsUserId())
                        .timestamp(ZonedDateTime.now())
                        .action(CheckInOutEnum.UNLOCK)
                        .build();
            } else if(action.equals("out")) {
                record = CheckInOutRecord.builder()
                        .dn(dn)
                        .timestamp(ZonedDateTime.now())
                        .action(CheckInOutEnum.CHECK_OUT)
                        .sessionId(checkInOut.getSessionId())
                        .build();
            }else{
                throw new RuntimeException("invalid action");
            }

            if(auth.getPrincipal() != null && auth.getPrincipal() instanceof AppUserDetails details){
                record.setOrganization(details.getOrganization());
                record.setEmployeeType(details.getEmployeeType());
            }

            return databaseService.saveCheckInOutRecord(record);
        }

    }

    /*
    @PostMapping("/out")
    public CheckInOutRecord checkOut(@RequestBody CheckInOut checkInOut) {
        log.info("Check-out request received: {}", checkInOut);

        Optional<CheckInOutRecord> optionalRecord = databaseService.lookupBySessionId(checkInOut.getSessionId());

        CheckInOutRecord record = CheckInOutRecord.builder()
                //.dn(auth.getName())
                .timestamp(ZonedDateTime.now())
                .action(CheckInOutEnum.CHECK_OUT)
                .sessionId(checkInOut.getSessionId())
                .build();

        if (optionalRecord.isPresent()) {
            record.setDn(optionalRecord.get().getDn());
        }

        return databaseService.saveCheckInOutRecord(record);
    }

     */

    @GetMapping("/records")
    public Map<String, Object> getRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortDir) {

        Sort sort = Sort.unsorted();
        if (sortField != null && sortDir != null) {
            sort = sortDir.equalsIgnoreCase("desc")
                    ? Sort.by(sortField).descending()
                    : Sort.by(sortField).ascending();
        }

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<CheckInOutRecord> userPage = databaseService.findTodaysRecords(pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("data", userPage.getContent());
        response.put("last_page", userPage.getTotalPages());
        response.put("total", userPage.getTotalElements());
        response.put("page", userPage.getNumber());

        return response;
    }
}
