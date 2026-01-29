package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.NotificationResponse;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Controller
@RequestMapping("/app/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationRepository notificationRepository;
    private final UserEventRecordRepository userEventRecordRepository;

    public NotificationController(NotificationRepository notificationRepository,
                                  UserEventRecordRepository userEventRecordRepository) {
        this.notificationRepository = notificationRepository;
        this.userEventRecordRepository = userEventRecordRepository;
    }

    @GetMapping("/id/{id}")
    public ModelAndView get(Authentication authentication, @PathVariable Long id) {
        ModelAndView mav = new ModelAndView("notification");

        NotificationRecord record = notificationRepository.findById(id).orElseThrow();

        mav.addObject("notification", record);

        NotificationResponse response = new NotificationResponse();
        response.setNotificationId(record.getId());

        if(record.getStatusResponse() != null){
            response.setResponse(record.getStatusResponse().name());
            response.setResponseTimestamp(DateTimeConstants.DATE_TIME_FORMATTER.format(record.getStatusResponseDate()));
            response.setResponderDn(record.getStatusResponseByDn());
        }

        mav.addObject("form", response);

        mav.addObject("notificationFor", authentication.getName());

        List<String> statuses = Stream.of(UserStatusEnum.values())
                .filter(e -> !e.isSelectable())
                .map(UserStatusEnum::name)
                .toList();

        mav.addObject("statuses", statuses);

        return mav;
    }

    @PostMapping("/update")
    public String update(Authentication authentication,
                         @ModelAttribute NotificationResponse notificationResponse,
                         RedirectAttributes redirectAttributes) throws IllegalAccessException {
        log.info("User {} updating notification response: {}", authentication.getName(), notificationResponse);

        Optional<NotificationRecord> optionalNotification =
                notificationRepository.findById(notificationResponse.getNotificationId());

        if(optionalNotification.isPresent()){
            NotificationRecord notificationRecord = optionalNotification.get();
            if(!notificationRecord.getForUserDn().equalsIgnoreCase(authentication.getName())){
                throw new IllegalAccessException("User %s is not authorized to update notification %d".formatted(authentication.getName(), notificationResponse.getNotificationId()));
            }

            UserStatusEnum status = UserStatusEnum.valueOf(notificationResponse.getResponse());

            notificationRecord.setStatusResponse(status);
            notificationRecord.setStatusResponseDate(ZonedDateTime.now());
            notificationRecord.setStatusResponseByDn(authentication.getName());
            notificationRecord = notificationRepository.save(notificationRecord);

            removeOtherNotifications(notificationRecord);

            updateEventRecord(notificationRecord, notificationRecord.getStatusResponse(), status);
        }

        redirectAttributes.addFlashAttribute("message", "Successfully updated notification");

        return "redirect:/app/notifications/id/" + notificationResponse.getNotificationId();
    }

    private void updateEventRecord(NotificationRecord notification, UserStatusEnum originalStatus, UserStatusEnum updatedStatus) {

        UserEventRecord userEventRecord = new UserEventRecord();
        userEventRecord.setDn(notification.getAboutUserDn());
        userEventRecord.setDate(notification.getNotificationDate().toLocalDate());

        if(originalStatus != null){
            UserEventRecord existing = userEventRecordRepository.findByDnIgnoreCaseAndDateAndStatusEquals(notification.getAboutUserDn(),
                            notification.getNotificationDate().toLocalDate(), originalStatus)
                    .orElse(null);
            if(existing != null){
                userEventRecord = existing;
            }
        }

        userEventRecord.setStatus(updatedStatus);

        userEventRecordRepository.save(userEventRecord);
    }

    private void removeOtherNotifications(NotificationRecord notification){
        notificationRepository.findByNotificationUuid(notification.getNotificationUuid())
                .forEach(n -> {
                    n.setStatusResponseByDn(notification.getStatusResponseByDn());
                    n.setStatusResponseDate(notification.getStatusResponseDate());
                    n.setStatusResponse(notification.getStatusResponse());
                    notificationRepository.save(n);
                });
    }
}
