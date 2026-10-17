package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.util.ValueValidatorUtil;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ReadyRoom's one way of e-mailing: a Thymeleaf template rendered and sent from
 * {@code application.notification-sender-email} through the configured mail server (SPRING_MAIL_HOST), with the
 * same STARTTLS and timeouts everywhere. Without a mail server nothing is sent, and that is not an error: what the
 * mail would have said is recorded in the database anyway (an absence alert, a status request).
 *
 * <p>{@link #send} sends now, on the caller's thread (the absence alert job). {@link #sendAfterCommit} waits until
 * the caller's transaction commits, so no mail is ever sent about something that was then rolled back, and then
 * sends on a background thread, so a slow mail server never holds a page.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templates;
    private final ApplicationProperties properties;
    private final Executor background;
    private final ExecutorService owned;

    @Autowired
    public Mailer(ObjectProvider<JavaMailSender> mailSender, SpringTemplateEngine templates,
                  ApplicationProperties properties) {
        this(mailSender.getIfAvailable(), templates, properties, null);
    }

    /** @param background where {@link #sendAfterCommit} sends; null for a single background thread of its own */
    public Mailer(JavaMailSender mailSender, SpringTemplateEngine templates, ApplicationProperties properties,
                  Executor background) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.properties = properties;
        if (background == null) {
            this.owned = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "readyroom-mail");
                thread.setDaemon(true);
                return thread;
            });
            this.background = owned;
        } else {
            this.owned = null;
            this.background = background;
        }
    }

    /** Whether a mail server is configured, so mail is actually sent. */
    public boolean isConfigured() {
        return mailSender != null;
    }

    /**
     * Renders {@code template} with {@code model} and sends it to {@code to} now. Failures are logged, never
     * thrown: a mail that cannot be sent never undoes what it was about.
     *
     * @return whether it was handed to the mail server
     */
    public boolean send(String to, String subject, String template, Map<String, Object> model) {
        if (mailSender == null) {
            log.debug("Not e-mailing \"{}\": no mail server is configured", subject);
            return false;
        }
        if (!ValueValidatorUtil.isValidEmail(to)) {
            log.error("Invalid email address: {}", to);
            return false;
        }
        try {
            Context context = new Context();
            context.setVariables(model);
            String html = templates.process(template, context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.getNotificationSenderEmail());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Failed to send \"{}\" to {}", subject, to, e);
            return false;
        }
    }

    /** {@link #send}, once the current transaction has committed (at once without one), off the caller's thread. */
    public void sendAfterCommit(String to, String subject, String template, Map<String, Object> model) {
        if (mailSender == null || to == null) {
            return;
        }
        Runnable task = () -> {
            if (send(to, subject, template, model)) {
                log.info("Mail \"{}\" sent to {}", subject, to);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    background.execute(task);
                }
            });
        } else {
            background.execute(task);
        }
    }

    @PreDestroy
    public void stop() {
        if (owned != null) {
            owned.shutdown();
        }
    }
}
