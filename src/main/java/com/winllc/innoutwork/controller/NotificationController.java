package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.NotificationResponse;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
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

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserEventRecordRepository userEventRecordRepository;

    @GetMapping("/id/{id}")
    public ModelAndView get(Authentication authentication, @PathVariable Long id) {
        ModelAndView mav = new ModelAndView("notification");

        NotificationRecord record = notificationRepository.findById(id).orElseThrow();

        mav.addObject("notification", record);

        NotificationResponse response = new NotificationResponse();
        response.setNotificationId(record.getId());

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
    public String update(@ModelAttribute NotificationResponse notificationResponse,
                         RedirectAttributes redirectAttributes) {

        Optional<NotificationRecord> optionalNotification =
                notificationRepository.findById(notificationResponse.getNotificationId());

        if(optionalNotification.isPresent()){
            NotificationRecord notificationRecord = optionalNotification.get();

            UserStatusEnum status = UserStatusEnum.valueOf(notificationResponse.getResponse());

            notificationRecord.setStatusResponse(status);
            notificationRecord.setStatusResponseDate(ZonedDateTime.now());
            notificationRepository.save(notificationRecord);

            UserEventRecord userEventRecord = new UserEventRecord();
            userEventRecord.setDn(notificationRecord.getAboutUserDn());
            userEventRecord.setStatus(status);
            userEventRecord.setDate(notificationRecord.getNotificationDate().toLocalDate());

            userEventRecordRepository.save(userEventRecord);
        }

        redirectAttributes.addFlashAttribute("message", "Successfully updated notification");

        return "redirect:/app/notifications/id/" + notificationResponse.getNotificationId();
    }
}
