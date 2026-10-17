package com.winllc.innoutwork.data;

import com.winllc.innoutwork.service.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventRangeTest {

    @Test
    void plainDatesAreTakenAsTheyAreWithAnExclusiveEnd() {
        EventRange range = EventRange.parse("2026-10-19", "2026-10-22", 366);

        assertEquals(LocalDate.of(2026, 10, 19), range.from());
        assertEquals(LocalDate.of(2026, 10, 21), range.lastDay());
        assertEquals(3, range.days());
        assertEquals(List.of(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21)),
                range.dates());
    }

    @Test
    void dateTimesAreTakenByTheirDatePartAsTheCalendarAlwaysSentThem() {
        EventRange range = EventRange.parse("2026-10-19T04:00:00.000Z", "2026-10-20T04:00:00.000Z", 366);

        assertEquals(LocalDate.of(2026, 10, 19), range.from());
        assertEquals(1, range.days());
    }

    @Test
    void aYearIsAllowedAndADayMoreIsNot() {
        assertEquals(366, EventRange.parse("2027-01-01", "2028-01-02", 366).days());

        InvalidRequestException tooLong = assertThrows(InvalidRequestException.class,
                () -> EventRange.parse("2027-01-01", "2028-01-03", 366));
        assertEquals("range_too_long", tooLong.error());
        assertEquals(400, tooLong.status().value());
    }

    @Test
    void anEmptyBackwardsOrUnreadableRangeIsRefused() {
        assertEquals("invalid_range", assertThrows(InvalidRequestException.class,
                () -> EventRange.parse("2026-10-19", "2026-10-19", 366)).error());
        assertEquals("invalid_range", assertThrows(InvalidRequestException.class,
                () -> EventRange.parse("2026-10-20", "2026-10-19", 366)).error());
        assertEquals("invalid_range", assertThrows(InvalidRequestException.class,
                () -> EventRange.parse("19/10/2026", "2026-10-20", 366)).error());
        assertEquals("invalid_range", assertThrows(InvalidRequestException.class,
                () -> EventRange.parse(null, "2026-10-20", 366)).error());
    }

    @Test
    void anInclusiveRangeCoversItsLastDay() {
        EventRange range = EventRange.inclusive(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 19));

        assertEquals(1, range.days());
        assertEquals(LocalDate.of(2026, 10, 19), range.lastDay());
    }
}
