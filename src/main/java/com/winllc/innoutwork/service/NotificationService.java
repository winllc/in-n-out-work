package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.util.ValueValidatorUtil;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.aspectj.weaver.ast.Not;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.LocalDate;
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
    private final LdapService ldapService;

    public NotificationService(JavaMailSender mailSender, NotificationRepository notificationRepository,
                               UserService userService, ApplicationProperties properties, LdapService ldapService, SpringTemplateEngine thymeleafTemplateEngine) {
        this.mailSender = mailSender;
        this.notificationRepository = notificationRepository;
        this.userService = userService;
        this.properties = properties;
        this.ldapService = ldapService;
        this.thymeleafTemplateEngine = thymeleafTemplateEngine;
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

    public void createAbsentNotification(String userDn){
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

            for(String managerDn : managerDns){

                Optional<LdapUser> managerOptional = ldapService.lookupUser(LdapDn.builder().dn(managerDn).build());

                if(managerOptional.isPresent()){
                    LdapUser managerUser = managerOptional.get();

                    NotificationRecord notificationRecord = new NotificationRecord();
                    notificationRecord.setNotificationUuid(notificationUuid);
                    notificationRecord.setType(NotificationTypeEnum.ABSENT);
                    notificationRecord.setAboutUserDn(userDn);
                    notificationRecord.setForUserDn(managerDn);
                    notificationRecord.setNotificationDate(ZonedDateTime.now());
                    notificationRecord.setExpectedCheckInTime(userRecord.getAverageLoginTime());

                    notificationRepository.save(notificationRecord);

                    if(managerUser.getEmail() != null) {
                        sendNotification(notificationRecord, managerUser.getEmail());
                    }else{
                        log.error("Manager does not have an email address: " + managerDn);
                    }
                }else{
                    log.error("Manager not found in LDAP: " + managerDn);
                }
            }

        }
    }

    public void sendNotification(NotificationRecord notification, String email) {
        if(ValueValidatorUtil.isValidEmail(email)) {
            try {
                String notificationUrl = properties.getApplicationBaseUrl() + "/app/notifications/id/" + notification.getId();

                Map<String, Object> templateModel = new HashMap<>();
                templateModel.put("for", LdapDn.builder().dn(notification.getForUserDn()).build().getCn());
                templateModel.put("expectedCheckIn", notification.getExpectedCheckInTime());
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
                log.info("Notification sent to user: " + email);
            }catch (Exception e) {
                log.error("Failed to send notification to " + email, e);
            }
        }else{
            log.error("Invalid email address: " + email);
        }
    }

}
