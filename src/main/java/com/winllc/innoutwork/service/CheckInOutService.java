package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.DateTimeConstants;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.util.Chunks;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CheckInOutService {

    private static final Logger log = LoggerFactory.getLogger(CheckInOutService.class);

    private final CheckInOutRecordRepository checkinInOutRecordRepository;
    private final UserRecordRepository userRecordRepository;
    /** The license's size: events from people it has no room for are not recorded. */
    private final TrackedPeopleLimit peopleLimit;

    public CheckInOutService(CheckInOutRecordRepository checkinInOutRecordRepository, UserRecordRepository userRecordRepository,
                             TrackedPeopleLimit peopleLimit) {
        this.checkinInOutRecordRepository = checkinInOutRecordRepository;
        this.userRecordRepository = userRecordRepository;
        this.peopleLimit = peopleLimit;
    }

    public long getCheckInOutRecordCount() {
        return checkinInOutRecordRepository.count();
    }

    /**
     * Stores a status event, first turning the day's first unlock into a check-in, and keeps the user's
     * average login time current.
     * <p>
     * One transaction, holding a lock on the user's record for check-ins and unlocks: both read before
     * they write (is this the first event today? what is the average now?), so two events for the same
     * user arriving together would otherwise both be promoted, or overwrite each other's average.
     * <p>
     * Someone with no user record is someone ReadyRoom does not track yet. Their events are refused with
     * {@link TrackedPeopleLimitException} while the license's size leaves no room for another person
     * (sign-in then lets them in without creating a record; see AppUserDetailsService).
     * <p>
     * A record must name someone: one without a DN is refused rather than stored as nobody's.
     */
    @Transactional
    public CheckInOutRecord saveCheckInOutRecord(CheckInOutRecord record) {
        if (record.getDn() == null || record.getDn().isBlank()) {
            throw new IllegalArgumentException("A " + record.getAction() + " event must name the user it belongs to");
        }

        boolean readsBeforeWriting = record.getAction() == CheckInOutEnum.UNLOCK || record.getAction() == CheckInOutEnum.CHECK_IN;
        Optional<UserRecord> lockedUser = readsBeforeWriting && record.getDn() != null
                ? userRecordRepository.findByDnForUpdate(record.getDn())
                : Optional.empty();

        if (record.getAction() != CheckInOutEnum.CHECK_OUT && record.getDn() != null && lockedUser.isEmpty()
                && userRecordRepository.findByDnIgnoreCase(record.getDn()).isEmpty()
                && !peopleLimit.mayAdd(userRecordRepository.count())) {
            throw new TrackedPeopleLimitException(record.getDn());
        }

        //if first record of day and is unlock, mark is as check_in;

        if(record.getAction() == CheckInOutEnum.UNLOCK){
            ZonedDateTime beginning = record.getZonedDateTimestamp().truncatedTo(ChronoUnit.DAYS);
            ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

            List<CheckInOutRecord> existingRecords = checkinInOutRecordRepository
                    .findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(beginning, ending, record.getDn());

            if(existingRecords.isEmpty()){
                // The day's first unlock is the user arriving, so it is recorded as a check-in.
                // Worth logging because the stored action differs from what was sent.
                log.debug("First activity of the day for {}; promoting UNLOCK to CHECK_IN", record.getDn());
                record.setAction(CheckInOutEnum.CHECK_IN);
            } else {
                log.debug("Unlock for {} follows {} earlier record(s) today; left as UNLOCK",
                        record.getDn(), existingRecords.size());
            }
        }

        // Only a check-in's address is needed (it binds its session id's logoff); no other record keeps one.
        if (record.getAction() != CheckInOutEnum.CHECK_IN) {
            record.setClientAddress(null);
        }

        // Saved before the average is recalculated, which reads check-ins back from the repository:
        // the average should include this check-in, and a user's first one should set it.
        CheckInOutRecord saved = checkinInOutRecordRepository.save(record);

        // One line per status event across the whole workforce, so this stays at debug.
        log.debug("Recorded {} for {} at {}", saved.getAction(), saved.getDn(), saved.getTimestamp());

        if(record.getAction() == CheckInOutEnum.CHECK_IN){
            Optional<UserRecord> recordOptional = lockedUser;
            if (recordOptional.isEmpty()) {
                // No stored record means no rolling average to maintain for this user.
                log.debug("No user record for {}; skipping average login update", record.getDn());
            }
            recordOptional.ifPresent(userRecord -> {

                LocalTime averageLogin = calculateAverageLogin(record.getDn());
                if (averageLogin != null) {
                    log.debug("Average login time for {} updated from {} to {}",
                            record.getDn(), userRecord.getAverageLoginTime(), averageLogin);
                    userRecord.setAverageLoginTime(averageLogin);

                    userRecordRepository.save(userRecord);
                }
            });
        }

        return saved;
    }

    private LocalTime calculateAverageLogin(String dn){
        ZonedDateTime from = ZonedDateTime.now().minusDays(30);
        ZonedDateTime to =ZonedDateTime.now();
        List<CheckInOutRecord> allCheckins = checkinInOutRecordRepository
                .findByDnIgnoreCaseAndTimestampIsBetweenAndActionEqualsOrderByTimestampDesc(dn, from, to, CheckInOutEnum.CHECK_IN);

        // Arrival is the day's first check-in. The workstation posts one on every Windows logon, so
        // a reboot or signing back in after lunch adds more, and averaging those would push the
        // expected login time, and with it the absence alerts, later in the day. Weekends are left
        // out as the absence check skips them: a quick Saturday sign-in says nothing about weekdays.
        List<ZonedDateTime> timestamps = allCheckins.stream()
                .filter(r -> r.getTimestamp() != null)
                .map(CheckInOutRecord::getZonedDateTimestamp)
                .filter(t -> !DateTimeConstants.WEEKEND_DAYS.contains(t.getDayOfWeek()))
                .collect(Collectors.toMap(ZonedDateTime::toLocalDate, t -> t, (a, b) -> a.isBefore(b) ? a : b))
                .values().stream()
                .toList();

        log.debug("Averaging the first check-in of {} weekday(s) from the last 30 days ({} check-ins) for {}",
                timestamps.size(), allCheckins.size(), dn);

        return calculateAverage(timestamps);
    }

    private static final long SECONDS_PER_DAY = 86_400;

    /**
     * The mean time of day, ignoring dates, to the second (fractions dropped).
     * <p>
     * Clock times wrap at midnight, so a plain mean of 23:30 and 00:30 would be noon. Each time is
     * first taken as whichever of itself or its copy a day earlier or later lies within twelve hours
     * of the times' circular mean, then averaged normally. Times that do not straddle midnight are
     * left as they are, so for them this is exactly the ordinary mean. When the circular mean is
     * undefined (times spread evenly round the clock) it falls back to the ordinary mean.
     */
    public static LocalTime calculateAverage(List<ZonedDateTime> timestamps) {
        if (timestamps == null || timestamps.isEmpty()) {
            return null;
        }

        long[] seconds = timestamps.stream()
                .mapToLong(t -> t.toLocalTime().toSecondOfDay())
                .toArray();

        double sin = 0;
        double cos = 0;
        for (long s : seconds) {
            double angle = 2 * Math.PI * s / SECONDS_PER_DAY;
            sin += Math.sin(angle);
            cos += Math.cos(angle);
        }
        double halfDay = SECONDS_PER_DAY / 2.0;
        double centre = Math.hypot(sin, cos) < 1e-9 * seconds.length
                ? halfDay
                : Math.floorMod(Math.round(Math.atan2(sin, cos) / (2 * Math.PI) * SECONDS_PER_DAY), SECONDS_PER_DAY);

        long sum = 0;
        for (long s : seconds) {
            if (s - centre >= halfDay) {
                sum += s - SECONDS_PER_DAY;
            } else if (centre - s > halfDay) {
                sum += s + SECONDS_PER_DAY;
            } else {
                sum += s;
            }
        }

        return LocalTime.ofSecondOfDay(Math.floorMod(Math.floorDiv(sum, seconds.length), SECONDS_PER_DAY));
    }

    /** What became of a logoff that could not sign in; see {@link #saveSessionCheckOut}. */
    public sealed interface SessionCheckOut {
        /** Recorded as the session's owner. */
        record Saved(CheckInOutRecord record) implements SessionCheckOut {
        }

        /** The id is no check-in's, already used or expired. Nothing stored. */
        record UnknownSession() implements SessionCheckOut {
        }

        /**
         * The id is usable but was issued to a logon from another address. Nothing stored, and the id stays usable
         * from its own address.
         */
        record OtherAddress(String dn, String checkInAddress) implements SessionCheckOut {
        }
    }

    /**
     * Records a logoff that could not sign in, as the user whose logon was answered with {@code sessionId}.
     * <p>
     * The Windows client's logoff runs after the user's profile has gone, so it has no certificate to sign in
     * with; it sends back the session id the logon's response carried. That id identifies the logoff only while
     * it is a check-in's, no logoff has used it yet, and the check-in is younger than {@code maxAge}; and, with
     * {@code bindToAddress}, only when the logoff comes from the address the check-in did (or the check-in is from
     * before addresses were recorded, {@link CheckInOutRecord#ANY_ADDRESS}). The check-in stays locked until this
     * transaction ends, so of two logoffs sent with the same id only the first counts.
     *
     * @param checkOut      the CHECK_OUT to store, without a DN: it is given the check-in's
     * @param clientAddress the logoff's client address, from {@link com.winllc.innoutwork.security.ClientAddress}
     */
    @Transactional
    public SessionCheckOut saveSessionCheckOut(CheckInOutRecord checkOut, String sessionId, String clientAddress,
                                               Duration maxAge, boolean bindToAddress) {
        Optional<CheckInOutRecord> session = usableSession(sessionId, maxAge);
        if (session.isEmpty()) {
            return new SessionCheckOut.UnknownSession();
        }
        CheckInOutRecord checkIn = session.get();
        String issuedTo = checkIn.getClientAddress();
        if (bindToAddress && !CheckInOutRecord.ANY_ADDRESS.equals(issuedTo)
                && (issuedTo == null || !issuedTo.equals(clientAddress))) {
            log.debug("Logoff session of {} presented from another address than its logon's", checkIn.getDn());
            return new SessionCheckOut.OtherAddress(LdapDn.normalize(checkIn.getDn()), issuedTo);
        }
        checkOut.setDn(LdapDn.normalize(checkIn.getDn()));
        checkOut.setSessionId(sessionId);
        checkOut.setClientAddress(null);
        return new SessionCheckOut.Saved(saveCheckInOutRecord(checkOut));
    }

    /**
     * Records a logoff from a signed-in user. It needs no session id, but one that still identifies a logoff is
     * used up, so that it cannot identify another; it must then be the user's own.
     *
     * @param checkOut the CHECK_OUT to store, with the signed-in user's DN
     * @throws AccessDeniedException if {@code sessionId} is still usable and belongs to somebody else
     */
    @Transactional
    public CheckInOutRecord saveSignedInCheckOut(CheckInOutRecord checkOut, String sessionId, Duration maxAge) {
        Optional<CheckInOutRecord> session = usableSession(sessionId, maxAge);
        String owner = session.map(r -> LdapDn.normalize(r.getDn())).orElse(null);
        if (owner != null && !owner.equalsIgnoreCase(LdapDn.normalize(checkOut.getDn()))) {
            throw new AccessDeniedException("%s sent the logoff session of %s".formatted(checkOut.getDn(), owner));
        }
        checkOut.setSessionId(session.isPresent() ? sessionId : null);
        return saveCheckInOutRecord(checkOut);
    }

    /** The check-in {@code sessionId} names, locked, while it can still identify a logoff; see saveSessionCheckOut. */
    private Optional<CheckInOutRecord> usableSession(String sessionId, Duration maxAge) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Optional<CheckInOutRecord> checkIn = checkinInOutRecordRepository
                .findBySessionIdAndAction(sessionId, CheckInOutEnum.CHECK_IN).stream()
                .filter(r -> r.getDn() != null && r.getTimestamp() != null)
                .findFirst();
        if (checkIn.isEmpty()) {
            log.debug("Logoff session id matches no check-in");
            return Optional.empty();
        }
        if (checkIn.get().getTimestamp().isBefore(ZonedDateTime.now().minus(maxAge))) {
            log.debug("Logoff session of {} is from {}, older than {}", checkIn.get().getDn(), checkIn.get().getTimestamp(), maxAge);
            return Optional.empty();
        }
        if (checkinInOutRecordRepository.existsBySessionIdAndAction(sessionId, CheckInOutEnum.CHECK_OUT)) {
            log.debug("Logoff session of {} has already been used", checkIn.get().getDn());
            return Optional.empty();
        }
        return checkIn;
    }

    public Page<CheckInOutRecord> findRecords(Pageable pageable, HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetween(beginning, ending, pageable);
    }

    /**
     * {@link #findRecordsForUser} for many users at once: the viewed day's records keyed by lower-cased DN,
     * in one query per {@link Chunks#IN_LIST_SIZE} DNs. Users with no records are absent from the map.
     */
    public Map<String, List<CheckInOutRecord>> findRecordsForUsers(Collection<String> dns, HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        Set<String> lower = new LinkedHashSet<>();
        dns.stream().filter(Objects::nonNull).forEach(dn -> lower.add(dn.toLowerCase()));

        Map<String, List<CheckInOutRecord>> byDn = new HashMap<>();
        for (List<String> chunk : Chunks.of(lower)) {
            for (CheckInOutRecord record : checkinInOutRecordRepository.findByLowercaseDnInAndTimestampBetween(chunk, beginning, ending)) {
                byDn.computeIfAbsent(record.getDn().toLowerCase(), k -> new ArrayList<>()).add(record);
            }
        }
        return byDn;
    }

    public List<CheckInOutRecord> findRecordsForUser(String dn, HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetweenAndDnIgnoreCaseOrderByTimestampDesc(beginning, ending, dn);
    }



    public List<CheckInOutRecord> findRecords(HttpSession session) {
        ZonedDateTime beginning = getDateTimeFromSession(session).truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime ending = beginning.plusDays(1).minusNanos(1);

        return checkinInOutRecordRepository.findByTimestampBetweenOrderByTimestampDesc(beginning, ending);
    }

    /**
     * The moment being viewed, in the server's zone.
     * <p>
     * Callers cut "the day" from this with {@code truncatedTo(DAYS)}, which works in whatever zone
     * the value carries. The date picker posts local midnight as a UTC ISO string, so left as sent
     * the day would be UTC midnight to midnight: 8pm the evening before to 8pm in New York.
     */
    public static ZonedDateTime getDateTimeFromSession(HttpSession session) {
        ZonedDateTime selectedDateTime =
                (ZonedDateTime) session.getAttribute("systemTime");

        if (selectedDateTime == null) {
            selectedDateTime = ZonedDateTime.now();
        }
        return selectedDateTime.withZoneSameInstant(ZoneId.systemDefault());
    }
}
