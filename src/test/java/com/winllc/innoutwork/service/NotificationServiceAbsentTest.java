package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The absence notification a manager receives: the record stored for it and the email rendered
 * from the real template.
 */
class NotificationServiceAbsentTest {

    private static final String BOB = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";
    private static final String ALICE = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";

    private JavaMailSender mailSender;
    private NotificationRepository notificationRepository;
    private UserService userService;
    private LdapService ldapService;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        notificationRepository = mock(NotificationRepository.class);
        when(notificationRepository.save(any(NotificationRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        userService = mock(UserService.class);
        ldapService = mock(LdapService.class);

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        notificationService = new NotificationService(mailSender, notificationRepository, userService,
                new ApplicationProperties(), ldapService, templateEngine);
    }

    private void bobReportsToAlice() {
        UserRecord bob = new UserRecord();
        bob.setDn(BOB);
        bob.setAverageLoginTime(LocalTime.of(8, 30));
        when(userService.getUserByDn(any())).thenReturn(Optional.of(bob));
        LdapUser alice = LdapUser.builder().dn(ALICE).email("alice@winllc.com").build();
        when(userService.getUserManager(any())).thenReturn(alice);
        when(ldapService.lookupUser(any(LdapDn.class))).thenReturn(Optional.of(alice));
    }

    @Test
    void theNotificationRecordsTheExpectedTimeItWasGiven() {
        bobReportsToAlice();

        notificationService.createAbsentNotification(BOB, LocalTime.of(11, 0));

        ArgumentCaptor<NotificationRecord> saved = ArgumentCaptor.forClass(NotificationRecord.class);
        verify(notificationRepository).save(saved.capture());
        assertEquals(LocalTime.of(11, 0), saved.getValue().getExpectedCheckInTime(),
                "should be the time the absence check used, not the average");
        assertEquals(BOB, saved.getValue().getAboutUserDn());
        assertEquals(ALICE, saved.getValue().getForUserDn());
        assertEquals(NotificationTypeEnum.ABSENT, saved.getValue().getType());
    }

    /** The row used to be bound to a variable nothing set, so it always went out blank. */
    @Test
    void theEmailShowsTheExpectedCheckInTime() throws Exception {
        bobReportsToAlice();

        notificationService.createAbsentNotification(BOB, LocalTime.of(11, 0));

        String html = sentHtml();
        assertTrue(Pattern.compile("Expected Check In:</strong></td>\\s*<td>11:00 ").matcher(html).find(), html);
        assertTrue(html.contains("Bob Barker"), html);
    }

    @Test
    void theEmailOmitsTheRowWhenNoTimeIsKnown() throws Exception {
        NotificationRecord notification = new NotificationRecord();
        notification.setAboutUserDn(BOB);
        notification.setForUserDn(ALICE);
        notification.setType(NotificationTypeEnum.ABSENT);
        notification.setNotificationDate(ZonedDateTime.now());

        notificationService.sendNotification(notification, "alice@winllc.com");

        String html = sentHtml();
        assertFalse(html.contains("Expected Check In"), html);
        assertTrue(html.contains("Bob Barker"), html);
    }

    private String sentHtml() throws Exception {
        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(message.capture());
        // A real sender does this before transmitting; it is what sets each part's content type.
        message.getValue().saveChanges();
        String html = findHtml(message.getValue());
        assertNotNull(html, "no HTML body in the sent message");
        return html;
    }

    private static String findHtml(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                String html = findHtml(child);
                if (html != null) {
                    return html;
                }
            }
        }
        return null;
    }
}
