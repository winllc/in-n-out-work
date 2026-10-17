package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.StatusClass;
import com.winllc.innoutwork.constant.UserStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which {@link StatusClass} each status is in, from the status model and {@code application.events.request-statuses}:
 * <ul>
 *   <li>a status listed there is a {@link StatusClass#REQUEST REQUEST}: approved before it counts (by default every
 *       status that excuses an absence: out of office, telework, TDY, scheduled leave, absent excused);</li>
 *   <li>any other status a person can pick ({@link UserStatusEnum#isSelectable()}) is
 *       {@link StatusClass#USER_SELECTABLE USER_SELECTABLE}: normal, early leave, late arrival;</li>
 *   <li>the rest are {@link StatusClass#ADMIN_ONLY ADMIN_ONLY}, recorded by managers and administrators for others:
 *       absent, absent unexcused, unscheduled leave.</li>
 * </ul>
 * Enforced on the server by every API that sets a status; the pages only offer what this allows.
 */
@Component
public class StatusPolicy {

    private static final Logger log = LoggerFactory.getLogger(StatusPolicy.class);

    private final Set<UserStatusEnum> requestStatuses;

    public StatusPolicy(ApplicationProperties properties) {
        List<UserStatusEnum> configured = properties.getEvents().getRequestStatuses();
        this.requestStatuses = configured == null || configured.isEmpty()
                ? EnumSet.noneOf(UserStatusEnum.class) : EnumSet.copyOf(configured);
    }

    public StatusClass classify(UserStatusEnum status) {
        if (requestStatuses.contains(status)) {
            return StatusClass.REQUEST;
        }
        return status.isSelectable() ? StatusClass.USER_SELECTABLE : StatusClass.ADMIN_ONLY;
    }

    /** The statuses in {@code statusClass}, in the enum's order. */
    public List<UserStatusEnum> statuses(StatusClass statusClass) {
        return Stream.of(UserStatusEnum.values()).filter(s -> classify(s) == statusClass).toList();
    }

    /** What a person may put on their own calendar: what they set, then what they request. */
    public List<UserStatusEnum> ownCalendarStatuses() {
        return Stream.of(UserStatusEnum.values()).filter(s -> classify(s) != StatusClass.ADMIN_ONLY).toList();
    }

    /**
     * An excusing status a person sets without approval stops their absence alerts on their word alone: allowed, if
     * an operator takes it out of the request list, but said at startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void describe() {
        List<UserStatusEnum> unapproved = Stream.of(UserStatusEnum.values())
                .filter(s -> s != UserStatusEnum.STANDARD && s.isExcusable() && classify(s) == StatusClass.USER_SELECTABLE)
                .toList();
        if (!unapproved.isEmpty()) {
            log.warn("Statuses {} excuse an absence but are not in application.events.request-statuses: people set"
                    + " them without approval, and they stop absence alerts", unapproved);
        }
        log.info("Status requests: {} need approval; {} are set by the person; {} only managers and administrators"
                        + " record", statuses(StatusClass.REQUEST), statuses(StatusClass.USER_SELECTABLE),
                statuses(StatusClass.ADMIN_ONLY));
    }
}
