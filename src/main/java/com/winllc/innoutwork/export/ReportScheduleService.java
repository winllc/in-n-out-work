package com.winllc.innoutwork.export;

import com.winllc.innoutwork.audit.AuditEntry;
import com.winllc.innoutwork.audit.AuditLog;
import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.model.ReportSchedule;
import com.winllc.innoutwork.repository.ReportScheduleRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.Mailer;
import com.winllc.innoutwork.service.TimeZones;
import com.winllc.innoutwork.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scheduled, e-mailed group attendance reports (RR-19). Only someone who may run a group's report (ADMIN, or a
 * MANAGER who manages the group) schedules it, and only people who may run it themselves receive it: so a report
 * never reaches anyone who could not see it in ReadyRoom. Both are checked when it is scheduled and again at every
 * run: a schedule whose owner no longer may is turned off, and recipients who no longer may are skipped. The report
 * is made with its owner's rights. Runs on one replica at a time (ScheduledReportsJob). Creating, deleting and
 * every sending are audited.
 */
@Service
public class ReportScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleService.class);
    static final String TEMPLATE = "scheduled-report-email";

    private final ReportScheduleRepository schedules;
    private final ReportExports exports;
    private final AccessPolicy accessPolicy;
    private final AppUserDetailsService users;
    private final LdapService ldapService;
    private final UserRecordRepository people;
    private final TimeZones timeZones;
    private final Mailer mailer;
    private final ApplicationProperties properties;
    private final AuditLog audit;
    private final Clock clock;

    @Autowired
    public ReportScheduleService(ReportScheduleRepository schedules, ReportExports exports, AccessPolicy accessPolicy,
                                 AppUserDetailsService users, LdapService ldapService, UserRecordRepository people,
                                 TimeZones timeZones, Mailer mailer, ApplicationProperties properties,
                                 ObjectProvider<AuditLog> audit) {
        this(schedules, exports, accessPolicy, users, ldapService, people, timeZones, mailer, properties,
                audit.getIfAvailable(() -> AuditLog.NOOP), Clock.systemUTC());
    }

    ReportScheduleService(ReportScheduleRepository schedules, ReportExports exports, AccessPolicy accessPolicy,
                          AppUserDetailsService users, LdapService ldapService, UserRecordRepository people,
                          TimeZones timeZones, Mailer mailer, ApplicationProperties properties, AuditLog audit,
                          Clock clock) {
        this.schedules = schedules;
        this.exports = exports;
        this.accessPolicy = accessPolicy;
        this.users = users;
        this.ldapService = ldapService;
        this.people = people;
        this.timeZones = timeZones;
        this.mailer = mailer;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    // --- who may ---------------------------------------------------------------------------------------------------

    /** Whether {@code who} may run {@code groupDn}'s report: ADMIN, or MANAGER and a manager of the group. */
    public boolean mayRunReport(Authentication who, String groupDn) {
        if (who == null) {
            return false;
        }
        boolean role = who.getAuthorities().stream().anyMatch(a -> UserRoleEnum.ADMIN.name().equals(a.getAuthority())
                || UserRoleEnum.MANAGER.name().equals(a.getAuthority()));
        return role && accessPolicy.canManageGroup(who, groupDn);
    }

    /** {@code dn} signed in as they are now, with their roles; empty when they are not in the directory. */
    Optional<Authentication> asUser(String dn) {
        try {
            UserDetails details = users.loadUserByUsername(dn);
            return Optional.of(UsernamePasswordAuthenticationToken.authenticated(dn, null, details.getAuthorities()));
        } catch (UsernameNotFoundException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // --- managing ----------------------------------------------------------------------------------------------------

    /**
     * Schedules {@code groupDn}'s report for {@code owner}.
     *
     * @param recipients DNs or e-mail addresses, one a line (or separated by commas or semicolons)
     * @throws AccessDeniedException   unless {@code owner} may run the report
     * @throws InvalidRequestException for a recipient who is not in the directory, has no e-mail address or may not
     *                                 run the report, too many recipients, or a bad hour or format
     */
    public ReportSchedule create(String name, String groupDn, ReportSchedule.Frequency frequency, String format,
                                 String recipients, int sendHour, Authentication owner) {
        if (!mayRunReport(owner, groupDn)) {
            throw new AccessDeniedException("Not a group this user may report on");
        }
        if (frequency == null) {
            throw InvalidRequestException.badRequest("bad_request", "Choose how often.");
        }
        if (TableWriter.Format.of(format) == null) {
            throw InvalidRequestException.badRequest("bad_request", "Choose CSV or Excel.");
        }
        if (sendHour < 0 || sendHour > 23) {
            throw InvalidRequestException.badRequest("bad_request", "The hour is 0 to 23.");
        }
        List<String> dns = resolveRecipients(recipients, groupDn);
        ZoneId zone = timeZones.zoneFor(people.findByDnIgnoreCase(owner.getName()).orElse(null));

        ReportSchedule schedule = new ReportSchedule();
        String cleanName = name == null || name.isBlank() ? LdapDn.cnOf(groupDn) + " attendance" : name.trim();
        schedule.setName(cleanName.length() > 200 ? cleanName.substring(0, 200) : cleanName);
        schedule.setOwnerDn(owner.getName());
        schedule.setGroupDn(groupDn);
        schedule.setFrequency(frequency);
        schedule.setFormat(TableWriter.Format.of(format).extension());
        schedule.setRecipients(String.join(";", dns));
        schedule.setTimeZone(zone.getId());
        schedule.setSendHour(sendHour);
        schedule.setEnabled(true);
        schedule.setCreatedAt(clock.instant());
        schedule.setNextRunAt(nextRun(frequency, sendHour, zone, clock.instant()));
        return audit.inTransaction(() -> {
            ReportSchedule saved = schedules.save(schedule);
            audit.record(entry("report.schedule-created", saved)
                    .detail("frequency", saved.getFrequency().name())
                    .detail("format", saved.getFormat())
                    .detail("recipients", saved.getRecipientList())
                    .detail("timeZone", saved.getTimeZone())
                    .detail("sendHour", saved.getSendHour()));
            return saved;
        });
    }

    /** The recipients as directory DNs, every one allowed to run the report. */
    List<String> resolveRecipients(String text, String groupDn) {
        Set<String> wanted = new LinkedHashSet<>();
        if (text != null) {
            for (String part : text.split("[\\r\\n;]+")) {
                // A DN holds commas; an address list may too: split on commas only between addresses.
                if (part.contains("=")) {
                    if (!part.isBlank()) {
                        wanted.add(part.trim());
                    }
                } else {
                    for (String address : part.split(",")) {
                        if (!address.isBlank()) {
                            wanted.add(address.trim());
                        }
                    }
                }
            }
        }
        if (wanted.isEmpty()) {
            throw InvalidRequestException.badRequest("bad_request", "Name at least one recipient.");
        }
        if (wanted.size() > properties.getReports().getMaxRecipients()) {
            throw InvalidRequestException.badRequest("bad_request", "At most " + properties.getReports().getMaxRecipients()
                    + " recipients.");
        }
        List<String> dns = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (String who : wanted) {
            Optional<LdapUser> user = who.contains("=")
                    ? safeLookup(() -> ldapService.lookupUser(new LdapDn(who)))
                    : safeLookup(() -> ldapService.lookupUser(properties.getUserLdapEmailAttribute(), who));
            if (user.isEmpty()) {
                refused.add(who + " (not in the directory)");
                continue;
            }
            String dn = user.get().getDn();
            if (email(user.get()) == null) {
                refused.add(LdapDn.cnOf(dn) + " (no e-mail address)");
            } else if (!asUser(dn).map(a -> mayRunReport(a, groupDn)).orElse(false)) {
                refused.add(LdapDn.cnOf(dn) + " (may not see this group's report)");
            } else if (dns.stream().noneMatch(d -> d.equalsIgnoreCase(dn))) {
                dns.add(dn);
            }
        }
        if (!refused.isEmpty()) {
            throw InvalidRequestException.badRequest("bad_recipients", "These cannot receive it: "
                    + String.join(", ", refused) + ". Recipients must be able to run this group's report themselves"
                    + " (an administrator, or a manager of the group with the manager role).");
        }
        return dns;
    }

    private static Optional<LdapUser> safeLookup(java.util.function.Supplier<Optional<LdapUser>> lookup) {
        try {
            return lookup.get();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String email(LdapUser user) {
        String mail = user.getEmail() != null && !user.getEmail().isBlank() ? user.getEmail() : user.getMail();
        return mail == null || mail.isBlank() ? null : mail.trim();
    }

    /** The schedules {@code viewer} sees: all for ADMIN, their own otherwise. */
    public List<ReportSchedule> list(Authentication viewer) {
        return accessPolicy.isAdmin(viewer) ? schedules.findAllByOrderByCreatedAtDesc()
                : schedules.findByOwner(viewer.getName());
    }

    /** Deletes a schedule: its owner or ADMIN. */
    public boolean delete(long id, Authentication by) {
        return audit.inTransaction(() -> schedules.findById(id)
                .filter(s -> accessPolicy.isAdmin(by) || s.getOwnerDn().equalsIgnoreCase(by.getName()))
                .map(s -> {
                    schedules.delete(s);
                    audit.record(entry("report.schedule-deleted", s));
                    return true;
                }).orElse(false));
    }

    /** Sends a schedule's report now, as if it were due (its owner or ADMIN); its next run is unchanged. */
    public Optional<String> sendNow(long id, Authentication by) {
        return schedules.findById(id)
                .filter(s -> accessPolicy.isAdmin(by) || s.getOwnerDn().equalsIgnoreCase(by.getName()))
                .map(s -> run(s, false));
    }

    // --- running -----------------------------------------------------------------------------------------------------

    /** Runs every schedule that is due. @return how many ran */
    public int runDue() {
        List<ReportSchedule> due = schedules.findDue(clock.instant());
        for (ReportSchedule schedule : due) {
            try {
                run(schedule, true);
            } catch (RuntimeException e) {
                log.error("Scheduled report {} failed", schedule.getId(), e);
                schedule.setLastResult("Failed: " + e.getClass().getSimpleName());
                schedule.setLastRunAt(clock.instant());
                schedule.setNextRunAt(nextRun(schedule.getFrequency(), schedule.getSendHour(), zone(schedule), clock.instant()));
                schedules.save(schedule);
            }
        }
        return due.size();
    }

    /** Makes and sends one schedule's report. @return what happened, as stored in its last result */
    String run(ReportSchedule schedule, boolean advance) {
        Instant now = clock.instant();
        ZoneId zone = zone(schedule);
        String result;
        Optional<Authentication> owner = asUser(schedule.getOwnerDn());
        if (owner.isEmpty() || !mayRunReport(owner.get(), schedule.getGroupDn())) {
            schedule.setEnabled(false);
            result = "Turned off: its owner may no longer run this group's report.";
            audit.recordQuietly(entry("report.schedule-disabled", schedule).detail("reason", "owner may no longer run it"));
        } else {
            result = send(schedule, owner.get(), zone, now);
        }
        schedule.setLastRunAt(now);
        schedule.setLastResult(result.length() > 500 ? result.substring(0, 500) : result);
        if (advance) {
            schedule.setNextRunAt(nextRun(schedule.getFrequency(), schedule.getSendHour(), zone, now));
        }
        schedules.save(schedule);
        log.info("Scheduled report {} ({}): {}", schedule.getId(), LogSafe.of(schedule.getName()), LogSafe.of(result));
        return result;
    }

    private String send(ReportSchedule schedule, Authentication owner, ZoneId zone, Instant now) {
        LocalDate[] period = period(schedule.getFrequency(), now.atZone(zone).toLocalDate());
        Map<String, String> recipients = new java.util.LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (String dn : schedule.getRecipientList()) {
            Optional<LdapUser> user = safeLookup(() -> ldapService.lookupUser(new LdapDn(dn)));
            String mail = user.map(ReportScheduleService::email).orElse(null);
            if (mail != null && asUser(dn).map(a -> mayRunReport(a, schedule.getGroupDn())).orElse(false)) {
                recipients.put(dn, mail);
            } else {
                skipped.add(LdapDn.cnOf(dn));
            }
        }
        if (recipients.isEmpty()) {
            return "Not sent: none of its recipients may receive it now.";
        }
        if (!mailer.isConfigured()) {
            return "Not sent: no mail server is configured.";
        }
        TableWriter.Format format = TableWriter.Format.of(schedule.getFormat());
        LimitedBuffer buffer = new LimitedBuffer(properties.getReports().getMaxAttachmentBytes());
        long rows;
        try {
            rows = exports.writeGroupAttendance(schedule.getGroupDn(), period[0], period[1], owner,
                    ReportExports.Zones.PERSON, format.open(buffer, "Attendance"));
        } catch (LimitedBuffer.TooLarge e) {
            return "Not sent: the report is larger than application.reports.max-attachment-bytes ("
                    + properties.getReports().getMaxAttachmentBytes() + " bytes).";
        } catch (IOException e) {
            return "Not sent: " + e.getMessage();
        }
        // Recorded before it goes out: a report that cannot be audited is not sent.
        audit.record(entry("data.exported", schedule)
                .detail("report", "group")
                .detail("via", "scheduled")
                .detail("format", format.extension())
                .detail("from", period[0].toString())
                .detail("to", period[1].toString())
                .detail("rows", rows)
                .detail("recipients", List.copyOf(recipients.keySet())));
        String filename = "readyroom-" + ExportFileNames.slug(LdapDn.cnOf(schedule.getGroupDn())) + "-" + period[0]
                + "-to-" + period[1] + "." + format.extension();
        int sent = 0;
        for (Map.Entry<String, String> r : recipients.entrySet()) {
            Map<String, Object> model = new HashMap<>();
            model.put("to", LdapDn.cnOf(r.getKey()));
            model.put("name", schedule.getName());
            model.put("group", LdapDn.cnOf(schedule.getGroupDn()));
            model.put("from", period[0].toString());
            model.put("until", period[1].toString());
            model.put("rows", rows);
            model.put("timeZone", schedule.getTimeZone());
            model.put("schedulesUrl", trimSlash(properties.getApplicationBaseUrl()) + "/app/reports/schedules");
            if (mailer.sendWithAttachment(r.getValue(), "ReadyRoom report: " + schedule.getName() + " (" + period[0]
                    + " to " + period[1] + ")", TEMPLATE, model, filename, buffer.toByteArray(), format.contentType())) {
                sent++;
            }
        }
        return "Sent to " + sent + " of " + recipients.size() + " recipient(s), " + rows + " rows, " + period[0]
                + " to " + period[1] + (skipped.isEmpty() ? "." : "; skipped (no longer allowed or no e-mail): "
                + String.join(", ", skipped) + ".");
    }

    // --- periods ------------------------------------------------------------------------------------------------------

    /** The days a run on {@code today} reports on: yesterday, last Monday to Sunday, or last month. */
    static LocalDate[] period(ReportSchedule.Frequency frequency, LocalDate today) {
        return switch (frequency) {
            case DAILY -> new LocalDate[]{today.minusDays(1), today.minusDays(1)};
            case WEEKLY -> {
                // The last full Monday-to-Sunday week before today.
                LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                yield new LocalDate[]{start, start.plusDays(6)};
            }
            case MONTHLY -> {
                LocalDate start = today.withDayOfMonth(1).minusMonths(1);
                yield new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
            }
        };
    }

    /** The first time after {@code after} it is due: at {@code hour} daily, on Mondays, or on the 1st. */
    static Instant nextRun(ReportSchedule.Frequency frequency, int hour, ZoneId zone, Instant after) {
        ZonedDateTime from = after.atZone(zone);
        LocalDate day = from.toLocalDate();
        for (int i = 0; i < 400; i++) {
            LocalDate candidate = day.plusDays(i);
            boolean fits = switch (frequency) {
                case DAILY -> true;
                case WEEKLY -> candidate.getDayOfWeek() == DayOfWeek.MONDAY;
                case MONTHLY -> candidate.getDayOfMonth() == 1;
            };
            if (fits) {
                ZonedDateTime at = candidate.atTime(hour, 0).atZone(zone);
                if (at.isAfter(from)) {
                    return at.toInstant();
                }
            }
        }
        throw new IllegalStateException("no next run");
    }

    private ZoneId zone(ReportSchedule schedule) {
        ZoneId zone = TimeZones.parse(schedule.getTimeZone());
        return zone == null ? timeZones.applicationZone() : zone;
    }

    private static AuditEntry entry(String action, ReportSchedule s) {
        return AuditEntry.of(AuditEntry.Category.DATA, action)
                .target("group", s.getGroupDn(), LdapDn.cnOf(s.getGroupDn()))
                .detail("scheduleId", s.getId())
                .detail("scheduleName", s.getName());
    }

    private static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Removes {@code dn} from every schedule: their own are deleted, and they are taken off others' recipients. */
    public long forget(String dn) {
        long changed = 0;
        for (ReportSchedule s : schedules.findByOwner(dn)) {
            schedules.delete(s);
            changed++;
        }
        String pattern = "%" + dn.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        for (ReportSchedule s : schedules.findByRecipientsLike(pattern)) {
            List<String> kept = s.getRecipientList().stream().filter(r -> !r.equalsIgnoreCase(dn)).toList();
            if (kept.size() != s.getRecipientList().size()) {
                s.setRecipients(String.join(";", kept));
                if (kept.isEmpty()) {
                    s.setEnabled(false);
                }
                schedules.save(s);
                changed++;
            }
        }
        return changed;
    }

    /** A byte buffer that refuses to grow past a limit. */
    static final class LimitedBuffer extends OutputStream {
        static final class TooLarge extends IOException {
            TooLarge() {
                super("too large");
            }
        }

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long limit;

        LimitedBuffer(long limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            check(1);
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check(len);
            bytes.write(b, off, len);
        }

        private void check(int more) throws TooLarge {
            if (bytes.size() + (long) more > limit) {
                throw new TooLarge();
            }
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }
}
