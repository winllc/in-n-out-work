package com.winllc.innoutwork.service;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.metrics.EnsMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    /** Sends on the caller's thread: the absence alert job, which runs on its own. */
    private final Mailer mailer;
    private final NotificationRepository notificationRepository;
    private final UserService userService;
    private final ApplicationProperties properties;
    private final LoadingCache<String, LdapUser> userCache;
    private final UserEventRecordRepository userEventRecordRepository;

    private AuditLog audit = AuditLog.NOOP;

    @Autowired(required = false)
    public void setAuditLog(AuditLog audit) {
        this.audit = audit;
    }

    /** Absence alerts and status changes for the webhooks; absent in tests that build this by hand. */
    private org.springframework.context.ApplicationEventPublisher events;

    @Autowired(required = false)
    public void setEventPublisher(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /** The mail sender is absent when no mail server is configured (SPRING_MAIL_HOST): alerts are then not sent. */
    @Autowired
    public NotificationService(ObjectProvider<JavaMailSender> mailSender, NotificationRepository notificationRepository,
                               UserService userService, ApplicationProperties properties,
                               @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache,
                               SpringTemplateEngine thymeleafTemplateEngine,
                               UserEventRecordRepository userEventRecordRepository) {
        this(mailSender.getIfAvailable(), notificationRepository, userService, properties, userCache,
                thymeleafTemplateEngine, userEventRecordRepository);
    }

    public NotificationService(JavaMailSender mailSender, NotificationRepository notificationRepository,
                               UserService userService, ApplicationProperties properties,
                               @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache,
                               SpringTemplateEngine thymeleafTemplateEngine,
                               UserEventRecordRepository userEventRecordRepository) {
        this.mailSender = mailSender;
        this.mailer = new Mailer(mailSender, thymeleafTemplateEngine, properties, Runnable::run);
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
                // absence.alert for the webhooks, queued once this transaction (if any) commits.
                com.winllc.innoutwork.webhook.WebhookEvents.absence(events, userDn,
                        notifications.getFirst().getNotificationDate().toLocalDate(), expectedCheckInTime,
                        notifications.size());
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
        com.winllc.innoutwork.webhook.WebhookEvents.calendar(events, notification.getAboutUserDn(), status,
                java.util.List.of(day), "manager");

        // In this method's transaction: the response and its audit record are committed together.
        audit.record(AuditEntry.of(AuditEntry.Category.DATA, "absence.alert-responded")
                .target("user", notification.getAboutUserDn(), LdapDn.cnOf(notification.getAboutUserDn()))
                .detail("notificationId", notificationId)
                .detail("day", day.toString())
                .change("status", previous == null ? null : previous.name(), status.name()));

        return copies.stream().filter(n -> n.getId().equals(notification.getId())).findFirst().orElse(notification);
    }

    public void sendNotification(NotificationRecord notification, String email) {
        if (!mailer.isConfigured()) {
            log.debug("Not e-mailing notification {}: no mail server is configured", notification.getId());
            return;
        }
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

        // Through the one mail path (Mailer): same sender, STARTTLS and timeouts as every other ReadyRoom mail.
        if (mailer.send(email, "Accountability Notification for "
                        + LdapDn.builder().dn(notification.getAboutUserDn()).build().getCn(),
                "accountability-notification.html", templateModel)) {
            EnsMetrics.alertSent();
            log.info("Notification {} about {} sent to {}",
                    notification.getId(), notification.getAboutUserDn(), email);
        }
    }

}
