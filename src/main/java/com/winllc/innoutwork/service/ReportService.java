package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.reports.DayReport;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.data.reports.UserReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.util.Chunks;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final LdapService ldapService;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;
    private final LoadingCache<String, LdapUser> userCache;

    public ReportService(LdapService ldapService, CheckInOutRecordRepository checkInOutRecordRepository,
                         UserEventRecordRepository userEventRecordRepository,
                         @Qualifier("ldapUserLoadingCache") LoadingCache<String, LdapUser> userCache) {
        this.ldapService = ldapService;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
        this.userCache = userCache;
    }

    public GroupReport generateGroupReport(LdapDn groupDn, ZonedDateTime from, ZonedDateTime to){
        long start = System.currentTimeMillis();

        // The completion line below carries the same identifiers plus the outcome.
        log.debug("Generating group report for {} from {} to {}",
                groupDn.dn(), from.toLocalDate(), to.toLocalDate());

        Optional<LdapGroup> groupOptional = ldapService.lookupGroup(groupDn);

        if(groupOptional.isPresent()){
            LdapGroup group = groupOptional.get();

            GroupReport groupReport = GroupReport.build(group, from.toLocalDate(), to.toLocalDate());
            List<String> groupMembers = ldapService.getGroupMembers(groupDn);

            log.debug("Group {} has {} member(s) to report on", group.getCn(), groupMembers.size());

            ZonedDateTime fromAtStartOfDay = from.toLocalDate().atStartOfDay(from.getZone());
            ZonedDateTime toAtEndOfDay = to.toLocalDate().atTime(23, 59, 59).atZone(from.getZone());

            List<UserReport> userReports = new ArrayList<>();

            // Every member's records and status entries for the whole range, a few queries in total rather
            // than one per member plus one per member per day.
            Set<String> lowerDns = new LinkedHashSet<>();
            groupMembers.stream().filter(Objects::nonNull).forEach(dn -> lowerDns.add(dn.toLowerCase()));
            Map<String, List<CheckInOutRecord>> recordsByDn = new HashMap<>();
            Map<String, Map<LocalDate, List<UserEventRecord>>> eventsByDn = new HashMap<>();
            for (List<String> chunk : Chunks.of(lowerDns)) {
                checkInOutRecordRepository.findByLowercaseDnInAndTimestampBetween(chunk, fromAtStartOfDay, toAtEndOfDay)
                        .forEach(r -> recordsByDn.computeIfAbsent(r.getDn().toLowerCase(), k -> new ArrayList<>()).add(r));
                userEventRecordRepository.findByLowercaseDnInAndDateBetween(chunk, fromAtStartOfDay.toLocalDate(), toAtEndOfDay.toLocalDate())
                        .forEach(e -> eventsByDn.computeIfAbsent(e.getDn().toLowerCase(), k -> new HashMap<>())
                                .computeIfAbsent(e.getDate(), k -> new ArrayList<>()).add(e));
            }

            for(String groupMember : groupMembers){
                // Through the user cache: the same people appear in reports, tables and notifications.
                LdapUser user = groupMember == null ? null : userCache.get(groupMember);

                if(user != null) {
                    List<CheckInOutRecord> memberRecords = new ArrayList<>(
                            recordsByDn.getOrDefault(groupMember.toLowerCase(), List.of()));
                    memberRecords.sort(Comparator.comparing(CheckInOutRecord::getTimestamp).reversed());
                    Map<LocalDate, List<UserEventRecord>> memberEvents =
                            eventsByDn.getOrDefault(groupMember.toLowerCase(), Map.of());

                    Map<LocalDate, List<CheckInOutRecord>> dateMap = createDateMap(memberRecords, fromAtStartOfDay, toAtEndOfDay);

                    List<UserDayReport> dayReports = new ArrayList<>();

                    dateMap.forEach((date, checkInOutRecords) -> {

                        Optional<UserEventRecord> eventRecordOptional = memberEvents.getOrDefault(date, List.of())
                                .stream()
                                .filter(r -> r.getStatus() != UserStatusEnum.STANDARD)
                                .findFirst();

                        UserDayReport dayReport = UserDayReport.build(date, eventRecordOptional.orElse(null), checkInOutRecords);

                        dayReports.add(dayReport);
                    });

                    UserReport userReport = UserReport.createUserReport(user, dayReports);
                    userReports.add(userReport);
                } else {
                    // A member DN that no longer resolves is silently absent from the
                    // report, which otherwise looks like missing data.
                    log.warn("Group member {} could not be resolved in the directory; omitted from the report",
                            groupMember);
                }
            }

            groupReport.getUserReports().addAll(userReports);

            groupReport.getDayReports().addAll(createDayReportMap(userReports, from, to).values());

            Collections.sort(groupReport.getDayReports());

            log.info("Generated group report for {}: {} user report(s), {} day report(s) in {}ms",
                    group.getCn(), userReports.size(), groupReport.getDayReports().size(),
                    System.currentTimeMillis() - start);

            return groupReport;
        }else{
            // Callers turn this null into an empty page; without a log the cause is invisible.
            log.warn("No group found for {}; cannot generate report", groupDn.dn());
            return null;
        }
    }

    private Map<String, DayReport> createDayReportMap(List<UserReport> userReports, ZonedDateTime from, ZonedDateTime to){
        Map<String, DayReport> reportMap = new HashMap<>();

        ZonedDateTime current = from;
        while(current.isBefore(to) || current.isEqual(to)) {
            LocalDate localDate = current.toLocalDate();
            reportMap.putIfAbsent(localDate.format(DATE_FORMATTER), new DayReport(localDate));
            current = current.plusDays(1);
        }

        for(UserReport userReport : userReports) {
            for(UserDayReport userDayReport : userReport.getDayReports()) {
                LocalDate date = userDayReport.getDay();

                DayReport dayReport = reportMap.get(date.format(DATE_FORMATTER));
                if(dayReport == null) {
                    dayReport = new DayReport();
                    dayReport.setDate(date);
                }

                dayReport.addUserReport(userReport);
                reportMap.put(date.format(DATE_FORMATTER), dayReport);
            }
        }

        return reportMap;
    }

    public static Map<LocalDate, List<CheckInOutRecord>> createDateMap(List<CheckInOutRecord> records, ZonedDateTime from, ZonedDateTime to){
        Map<LocalDate, List<CheckInOutRecord>> collect = records.stream()
                .collect(Collectors.groupingBy(r -> r.getZonedDateTimestamp().toLocalDate()));

        ZonedDateTime current = from;
        while(current.isBefore(to) || current.isEqual(to)) {
            LocalDate localDate = current.toLocalDate();
            collect.putIfAbsent(localDate, List.of());
            current = current.plusDays(1);
        }
        return collect;
    }
}
