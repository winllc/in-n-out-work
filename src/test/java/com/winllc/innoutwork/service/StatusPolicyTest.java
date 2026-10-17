package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.StatusClass;
import com.winllc.innoutwork.constant.UserStatusEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.winllc.innoutwork.constant.UserStatusEnum.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three classes of status, from the status model and application.events.request-statuses. */
class StatusPolicyTest {

    private static StatusPolicy policy(List<UserStatusEnum> requestStatuses) {
        ApplicationProperties properties = new ApplicationProperties();
        if (requestStatuses != null) {
            properties.getEvents().setRequestStatuses(new ArrayList<>(requestStatuses));
        }
        return new StatusPolicy(properties);
    }

    @Test
    void byDefaultEveryStatusThatExcusesAnAbsenceIsRequested() {
        StatusPolicy policy = policy(null);

        assertEquals(List.of(OUT_OF_OFFICE, WORK_FROM_HOME, TDY, SCHEDULED_LEAVE, ABSENT_EXCUSED),
                policy.statuses(StatusClass.REQUEST));
        assertEquals(List.of(STANDARD, EARLY_LEAVE, LATE_ARRIVAL), policy.statuses(StatusClass.USER_SELECTABLE));
        assertEquals(List.of(UNSCHEDULED_LEAVE, ABSENT_UNEXCUSED, ABSENT), policy.statuses(StatusClass.ADMIN_ONLY));
    }

    @Test
    void nothingAPersonSetsThemselvesExcusesAnAbsence() {
        StatusPolicy policy = policy(null);

        for (UserStatusEnum status : policy.statuses(StatusClass.USER_SELECTABLE)) {
            assertTrue(status == STANDARD || !status.isExcusable(), status + " excuses an absence without approval");
        }
    }

    @Test
    void theOwnCalendarOffersWhatIsSetAndWhatIsRequestedButNoManagersStatus() {
        List<UserStatusEnum> offered = policy(null).ownCalendarStatuses();

        assertTrue(offered.containsAll(List.of(STANDARD, LATE_ARRIVAL, SCHEDULED_LEAVE, ABSENT_EXCUSED)));
        assertFalse(offered.contains(ABSENT));
        assertFalse(offered.contains(ABSENT_UNEXCUSED));
        assertFalse(offered.contains(UNSCHEDULED_LEAVE));
    }

    @Test
    void anOperatorCanLetPeopleSetTeleworkThemselves() {
        StatusPolicy policy = policy(List.of(SCHEDULED_LEAVE, ABSENT_EXCUSED));

        assertEquals(StatusClass.USER_SELECTABLE, policy.classify(WORK_FROM_HOME));
        assertEquals(StatusClass.REQUEST, policy.classify(SCHEDULED_LEAVE));
        // Not selectable in the model, and no longer requested: only managers record it.
        assertEquals(StatusClass.ADMIN_ONLY, policy.classify(UNSCHEDULED_LEAVE));
    }

    @Test
    void anEmptyListMakesNothingARequest() {
        StatusPolicy policy = policy(List.of());

        assertEquals(List.of(), policy.statuses(StatusClass.REQUEST));
        assertEquals(StatusClass.ADMIN_ONLY, policy.classify(ABSENT_EXCUSED));
    }

    @Test
    void standardCannotBeARequestStatus() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ApplicationProperties.Events events = new ApplicationProperties.Events();
        events.setRequestStatuses(new ArrayList<>(List.of(STANDARD, SCHEDULED_LEAVE)));

        Set<ConstraintViolation<ApplicationProperties.Events>> violations = validator.validate(events);

        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("application.events.request-statuses"));
    }

    @Test
    void theRangeCapIsValidated() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ApplicationProperties.Events events = new ApplicationProperties.Events();

        events.setMaxRangeDays(0);
        assertTrue(validator.validate(events).iterator().next().getMessage()
                .contains("application.events.max-range-days"));
        events.setMaxRangeDays(366);
        assertTrue(validator.validate(events).isEmpty());
    }
}
