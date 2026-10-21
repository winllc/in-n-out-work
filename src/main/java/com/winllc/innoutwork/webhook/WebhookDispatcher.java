package com.winllc.innoutwork.webhook;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends queued webhook deliveries every few seconds, on one replica at a time (ShedLock), so none goes out twice
 * at once; and once an hour prunes the delivery log.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookService webhooks;

    public WebhookDispatcher(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Scheduled(fixedDelayString = "${application.webhooks.poll-millis:5000}", initialDelayString = "${application.webhooks.poll-millis:5000}")
    @SchedulerLock(name = "WebhookDispatcher", lockAtMostFor = "PT10M")
    public void send() {
        if (!webhooks.isConfigured()) {
            return;
        }
        try {
            int tried;
            int rounds = 0;
            do {
                tried = webhooks.deliverDue();
                rounds++;
            } while (tried == WebhookService.BATCH && rounds < 20);
        } catch (RuntimeException e) {
            log.error("Sending webhook deliveries failed", e);
        }
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    @SchedulerLock(name = "WebhookLogPrune", lockAtMostFor = "PT10M")
    public void prune() {
        if (!webhooks.isConfigured()) {
            return;
        }
        int removed = webhooks.pruneLog();
        if (removed > 0) {
            log.info("Pruned {} finished webhook deliveries from the log", removed);
        }
    }
}
