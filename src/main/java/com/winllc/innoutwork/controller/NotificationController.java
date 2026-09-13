package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.NotificationResponse;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Stream;

@Controller
@RequestMapping("/app/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    public NotificationController(NotificationRepository notificationRepository,
                                  NotificationService notificationService) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
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
                         RedirectAttributes redirectAttributes) {
        log.info("User {} updating notification response: {}", authentication.getName(), notificationResponse);

        if (notificationRepository.existsById(notificationResponse.getNotificationId())) {
            notificationService.recordResponse(notificationResponse.getNotificationId(), authentication.getName(),
                    UserStatusEnum.valueOf(notificationResponse.getResponse()));
        }

        redirectAttributes.addFlashAttribute("message", "Successfully updated notification");

        return "redirect:/app/notifications/id/" + notificationResponse.getNotificationId();
    }
}
