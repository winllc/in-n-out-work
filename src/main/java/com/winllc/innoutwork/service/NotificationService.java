package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.security.AppUserDetailsService;
import org.aspectj.weaver.ast.Not;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final JavaMailSender mailSender;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private ApplicationProperties properties;
    @Autowired
    private LdapService ldapService;

    public NotificationService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
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

            for(String managerDn : managerDns){

                Optional<LdapUser> managerOptional = ldapService.lookupUser(LdapDn.builder().dn(managerDn).build());

                if(managerOptional.isPresent()){
                    LdapUser managerUser = managerOptional.get();

                    NotificationRecord notificationRecord = new NotificationRecord();
                    notificationRecord.setType(NotificationTypeEnum.ABSENT);
                    notificationRecord.setAboutUserDn(userDn);
                    notificationRecord.setForUserDn(managerDn);
                    notificationRecord.setNotificationDate(ZonedDateTime.now());

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

    public void updateNotification(){

    }

    public void sendNotification(NotificationRecord notification, String email) {
        SimpleMailMessage mailMessage = new SimpleMailMessage();
        mailMessage.setTo(email);
        mailMessage.setFrom(properties.getNotificationSenderEmail());
        mailMessage.setSubject("Accountability Notification");
        mailMessage.setText(notification.getSummary());
        mailSender.send(mailMessage);
        log.info("Notification sent to user: " + email);
    }

}
