package com.winllc.innoutwork.service;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.NotificationTypeEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.NotificationRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.NotificationRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.List;
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
    private LoadingCache<String, LdapUser> userCache;
    private UserEventRecordRepository events;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        notificationRepository = mock(NotificationRepository.class);
        when(notificationRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        userService = mock(UserService.class);
        @SuppressWarnings("unchecked")
        LoadingCache<String, LdapUser> cache = mock(LoadingCache.class);
        userCache = cache;
        events = mock(UserEventRecordRepository.class);
        when(events.save(any(UserEventRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resolver);

        notificationService = new NotificationService(mailSender, notificationRepository, userService,
                new ApplicationProperties(), userCache, templateEngine, events);
    }

    private void bobReportsToAlice() {
        UserRecord bob = new UserRecord();
        bob.setDn(BOB);
        bob.setAverageLoginTime(LocalTime.of(8, 30));
        when(userService.getUserByDn(any())).thenReturn(Optional.of(bob));
        LdapUser alice = LdapUser.builder().dn(ALICE).email("alice@winllc.com").build();
        when(userService.getUserManager(any())).thenReturn(alice);
        when(userCache.get(ALICE)).thenReturn(alice);
    }

    @Test
    void theNotificationRecordsTheExpectedTimeItWasGiven() {
        bobReportsToAlice();

        notificationService.createAbsentNotification(BOB, LocalTime.of(11, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationRecord>> saved = ArgumentCaptor.forClass(List.class);
        verify(notificationRepository).saveAll(saved.capture());
        NotificationRecord record = saved.getValue().getFirst();
        assertEquals(LocalTime.of(11, 0), record.getExpectedCheckInTime(),
                "should be the time the absence check used, not the average");
        assertEquals(BOB, record.getAboutUserDn());
        assertEquals(ALICE, record.getForUserDn());
        assertEquals(NotificationTypeEnum.ABSENT, record.getType());
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

    // --- recording a manager's response ---------------------------------------------------------------

    private NotificationRecord copy(long id, String forDn, UserStatusEnum response) {
        return NotificationRecord.builder().id(id).notificationUuid("u1").aboutUserDn(BOB).forUserDn(forDn)
                .type(NotificationTypeEnum.ABSENT).statusResponse(response)
                .notificationDate(ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, ZoneId.systemDefault())).build();
    }

    /** Every manager's copy of the alert shows the response, and it becomes the user's status that day. */
    @Test
    void aResponseIsCopiedToEveryManagerAndRecordedAsTheDaysStatus() {
        NotificationRecord mine = copy(1, ALICE, null);
        NotificationRecord other = copy(2, "cn=Carol Clark,ou=Users,dc=winllc,dc=com", null);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(mine));
        when(notificationRepository.findByNotificationUuid("u1")).thenReturn(List.of(mine, other));

        notificationService.recordResponse(1L, ALICE.toUpperCase(), UserStatusEnum.ABSENT_EXCUSED);

        assertEquals(UserStatusEnum.ABSENT_EXCUSED, other.getStatusResponse());
        assertEquals(ALICE.toUpperCase(), other.getStatusResponseByDn());
        assertNotNull(mine.getStatusResponseDate());
        verify(notificationRepository).saveAll(List.of(mine, other));
        ArgumentCaptor<UserEventRecord> event = ArgumentCaptor.forClass(UserEventRecord.class);
        verify(events).save(event.capture());
        assertEquals(BOB, event.getValue().getDn());
        assertEquals(java.time.LocalDate.of(2026, 9, 10), event.getValue().getDate());
        assertEquals(UserStatusEnum.ABSENT_EXCUSED, event.getValue().getStatus());
    }

    /** Changing an earlier response replaces the status it recorded rather than adding another. */
    @Test
    void changingAResponseReplacesTheStatusItRecorded() {
        NotificationRecord mine = copy(1, ALICE, UserStatusEnum.ABSENT_EXCUSED);
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(mine));
        when(notificationRepository.findByNotificationUuid("u1")).thenReturn(List.of(mine));
        UserEventRecord earlier = UserEventRecord.builder().id(9L).dn(BOB)
                .date(java.time.LocalDate.of(2026, 9, 10)).status(UserStatusEnum.ABSENT_EXCUSED).build();
        when(events.findByDnIgnoreCaseAndDateAndStatusEquals(BOB, java.time.LocalDate.of(2026, 9, 10), UserStatusEnum.ABSENT_EXCUSED))
                .thenReturn(Optional.of(earlier));

        notificationService.recordResponse(1L, ALICE, UserStatusEnum.LATE_ARRIVAL);

        verify(events).save(earlier);
        assertEquals(UserStatusEnum.LATE_ARRIVAL, earlier.getStatus());
    }

    @Test
    void onlyTheManagerTheNotificationWasSentToCanRespond() {
        when(notificationRepository.findById(1L)).thenReturn(Optional.of(copy(1, ALICE, null)));

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> notificationService.recordResponse(1L, BOB, UserStatusEnum.ABSENT_EXCUSED));

        verify(notificationRepository, org.mockito.Mockito.never()).saveAll(any());
    }
}
