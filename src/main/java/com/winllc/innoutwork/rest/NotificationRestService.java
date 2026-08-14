package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationRestService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRestService.class);

    private final NotificationRepository notificationRepository;

    public NotificationRestService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @GetMapping("/my")
    public List<NotificationRecord> getMyNotifications(Authentication authentication) {
        List<NotificationRecord> records = notificationRepository.findByForUserDnIgnoreCaseAndStatusResponseDateNull(
                authentication.getName());

        return records;
    }

    @PostMapping("/markAllAsRead")
    public void markAllAsRead(Authentication authentication) {
        log.debug("All notifications marked as read for user: {}", authentication.getName());

        List<NotificationRecord> records = notificationRepository.findByForUserDnIgnoreCaseAndStatusResponseDateNull(
                authentication.getName());

        for(NotificationRecord record : records){
            record.setStatusResponseDate(java.time.ZonedDateTime.now());
            notificationRepository.save(record);
        }
    }
}
