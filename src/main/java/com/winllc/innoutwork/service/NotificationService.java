package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.util.ValueValidatorUtil;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SpringTemplateEngine thymeleafTemplateEngine;
    private final JavaMailSender mailSender;
    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;
    private final UserEventRecordRepository userEventRecordRepository;

    public NotificationService(JavaMailSender mailSender, NotificationRepository notificationRepository,
                               UserService userService, ApplicationProperties properties,
                               @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache,
                               SpringTemplateEngine thymeleafTemplateEngine,
                               UserEventRecordRepository userEventRecordRepository) {
        this.mailSender = mailSender;
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.properties = properties;
        this.userCache = userCache;
        this.thymeleafTemplateEngine = thymeleafTemplateEngine;
        this.userEventRecordRepository = userEventRecordRepository;
    }

    public List<NotificationRecord> getNotificationsForUser(String dn){
        return notificationRepository.findByForUserDnIgnoreCase(dn);
    }

    public List<NotificationRecord> getNotificationsForUserFromToday(String dn){
        LocalDate today = LocalDate.now();
        ZonedDateTime startOfDay = today.atStartOfDay(ZonedDateTime.now().getZone());
        ZonedDateTime endOfDay = startOfDay.plusDays(1).minusNanos(1);

        return notificationRepository.findByAboutUserDnIgnoreCaseAndNotificationDateBetween(dn, startOfDay, endOfDay);
    }

    /**
     * @param expectedCheckInTime the time the user was expected in by, as the absence check decided it;
     *                            recorded on the notification and shown in the email
     */
    public void createAbsentNotification(String userDn, LocalTime expectedCheckInTime){
        Optional<UserRecord> aboutUserOptional = userService.getUserByDn(LdapDn.builder().dn(userDn).build());

        if(aboutUserOptional.isPresent()){
            UserRecord userRecord = aboutUserOptional.get();

            List<String> managerDns = new ArrayList<>();

            if(userRecord.getAltManagerList() != null && !userRecord.getAltManagerList().isEmpty()){
                managerDns.addAll(userRecord.getAltManagerList());
            }

            LdapUser userManager = userService.getUserManager(LdapDn.builder().dn(userDn).build());
            if(userManager != null){
                managerDns.add(userManager.getDn());
            }

            String notificationUuid = UUID.randomUUID().toString();

            // One notification per manager, saved together: either every manager gets the alert or none
            // does, and emails only go out once the records exist.
            List<NotificationRecord> notifications = new ArrayList<>();
            Map<NotificationRecord, String> emails = new IdentityHashMap<>();
            for(String managerDn : managerDns){
                // The user cache: managers are the same few people alert after alert.
                LdapUser managerUser = userCache.get(managerDn);

                if(managerUser != null){
                    NotificationRecord notificationRecord = new NotificationRecord();
                    notificationRecord.setNotificationUuid(notificationUuid);
                    notificationRecord.setType(NotificationTypeEnum.ABSENT);
                    notificationRecord.setAboutUserDn(userDn);
                    notificationRecord.setForUserDn(managerDn);
                    notificationRecord.setNotificationDate(ZonedDateTime.now());
                    notificationRecord.setExpectedCheckInTime(expectedCheckInTime);
                    notifications.add(notificationRecord);

                    if(managerUser.getEmail() != null) {
                        emails.put(notificationRecord, managerUser.getEmail());
                    }else{
                        log.error("Manager does not have an email address: {}", managerDn);
                    }
                }else{
                    log.error("Manager not found in LDAP: {}", managerDn);
                }
            }

            if (!notifications.isEmpty()) {
                notificationRepository.saveAll(notifications);
                emails.forEach(this::sendNotification);
            }
        }
    }

    /**
     * Records a manager's response to an alert: on the notification, on the other managers' copies of the
     * same alert, and as the user's status for that day. All or nothing.
     *
     * @throws AccessDeniedException if the notification was not sent to {@code responderDn}
     */
    @Transactional
    public NotificationRecord recordResponse(Long notificationId, String responderDn, UserStatusEnum status) {
        NotificationRecord notification = notificationRepository.findById(notificationId).orElseThrow();
        if (!notification.getForUserDn().equalsIgnoreCase(responderDn)) {
            throw new AccessDeniedException("User %s is not authorized to update notification %d"
                    .formatted(responderDn, notificationId));
        }

        // The status this alert previously recorded, if any, is the day's status entry to replace.
        UserStatusEnum previous = notification.getStatusResponse();
        ZonedDateTime respondedAt = ZonedDateTime.now();

        List<NotificationRecord> copies = new ArrayList<>(notificationRepository.findByNotificationUuid(notification.getNotificationUuid()));
        if (copies.stream().noneMatch(n -> n.getId().equals(notification.getId()))) {
            copies.add(notification);
        }
        for (NotificationRecord copy : copies) {
            copy.setStatusResponse(status);
            copy.setStatusResponseDate(respondedAt);
            copy.setStatusResponseByDn(responderDn);
        }
        notificationRepository.saveAll(copies);

        LocalDate day = notification.getNotificationDate().toLocalDate();
        UserEventRecord event = previous == null ? null : userEventRecordRepository
                .findByDnIgnoreCaseAndDateAndStatusEquals(notification.getAboutUserDn(), day, previous)
                .orElse(null);
        if (event == null) {
            event = new UserEventRecord();
            event.setDn(notification.getAboutUserDn());
            event.setDate(day);
        }
        event.setStatus(status);
        userEventRecordRepository.save(event);

        return copies.stream().filter(n -> n.getId().equals(notification.getId())).findFirst().orElse(notification);
    }

    public void sendNotification(NotificationRecord notification, String email) {
        if(ValueValidatorUtil.isValidEmail(email)) {
            try {
                String notificationUrl = properties.getApplicationBaseUrl() + "/app/notifications/id/" + notification.getId();

                log.debug("Sending {} notification {} about {} to {}", notification.getType(),
                        notification.getId(), notification.getAboutUserDn(), email);

                Map<String, Object> templateModel = new HashMap<>();
                templateModel.put("for", LdapDn.builder().dn(notification.getForUserDn()).build().getCn());
                templateModel.put("expectedCheckIn", notification.getExpectedCheckInTime() == null ? null
                        : DateTimeConstants.TIME_FORMATTER.withZone(ZoneId.systemDefault()).format(notification.getExpectedCheckInTime()));
                templateModel.put("aboutUser", LdapDn.builder().dn(notification.getAboutUserDn()).build().getCn());
                templateModel.put("type", notification.getType());
                templateModel.put("notificationDate", DateTimeConstants.DATE_FORMATTER.format(notification.getNotificationDate()));
                templateModel.put("notificationUrl", notificationUrl);

                Context thymeleafContext = new Context();
                thymeleafContext.setVariables(templateModel);
                String htmlBody = thymeleafTemplateEngine.process("accountability-notification.html", thymeleafContext);

                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(properties.getNotificationSenderEmail());
                helper.setTo(email);
                helper.setSubject("Accountability Notification for " + LdapDn.builder().dn(notification.getAboutUserDn()).build().getCn());
                helper.setText(htmlBody, true);

                mailSender.send(message);
                log.info("Notification {} about {} sent to {}",
                        notification.getId(), notification.getAboutUserDn(), email);
            }catch (Exception e) {
                log.error("Failed to send notification to {}", email, e);
            }
        }else{
            log.error("Invalid email address: {}", email);
        }
    }

}
