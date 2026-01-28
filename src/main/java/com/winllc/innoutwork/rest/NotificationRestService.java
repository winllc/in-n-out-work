package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.data.NotificationResponse;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationRestService {

    @Autowired
    private NotificationRepository notificationRepository;

    @GetMapping("/my")
    public List<NotificationRecord> getMyNotifications(Authentication authentication) {
        // Implementation goes here

        List<NotificationRecord> records = notificationRepository.findByForUserDnIgnoreCaseAndStatusResponseDateNull(
                authentication.getName());

        return records;
    }

    @PostMapping("/updateStatus")
    public void updateStatus(Authentication authentication,
                             @RequestBody NotificationResponse response) {
        // Implementation goes here
        NotificationRecord record = notificationRepository.findById(response.getNotificationId()).orElse(null);
        if(record != null){
            if(authentication.getName().equalsIgnoreCase(record.getForUserDn())){

            }else{
                throw new RuntimeException("Unauthorized");
            }
        }
    }

    @PostMapping("/markAllAsRead")
    public void markAllAsRead(Authentication authentication) {
        List<NotificationRecord> records = notificationRepository.findByForUserDnIgnoreCaseAndStatusResponseDateNull(
                authentication.getName());

        for(NotificationRecord record : records){
            record.setStatusResponseDate(java.time.ZonedDateTime.now());
            notificationRepository.save(record);
        }
    }
}
