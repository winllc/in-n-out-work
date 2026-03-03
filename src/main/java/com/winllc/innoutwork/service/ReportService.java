package com.winllc.innoutwork.service;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.reports.DayReport;
import com.winllc.innoutwork.data.reports.UserDayReport;
import com.winllc.innoutwork.data.reports.GroupReport;
import com.winllc.innoutwork.data.reports.UserReport;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.winllc.innoutwork.constant.DateTimeConstants.DATE_FORMATTER;

@Service
public class ReportService {

    private final LdapService ldapService;
    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final UserEventRecordRepository userEventRecordRepository;

    public ReportService(LdapService ldapService, CheckInOutRecordRepository checkInOutRecordRepository,
                         UserEventRecordRepository userEventRecordRepository) {
        this.ldapService = ldapService;
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.userEventRecordRepository = userEventRecordRepository;
    }

    public GroupReport generateGroupReport(LdapDn groupDn, ZonedDateTime from, ZonedDateTime to){
        Optional<LdapGroup> groupOptional = ldapService.lookupGroup(groupDn);

        if(groupOptional.isPresent()){
            LdapGroup group = groupOptional.get();

            GroupReport groupReport = GroupReport.build(group, from.toLocalDate(), to.toLocalDate());
            List<String> groupMembers = ldapService.getGroupMembers(groupDn);

            ZonedDateTime fromAtStartOfDay = from.toLocalDate().atStartOfDay(from.getZone());
            ZonedDateTime toAtEndOfDay = to.toLocalDate().atTime(23, 59, 59).atZone(from.getZone());

            List<UserReport> userReports = new ArrayList<>();

            for(String groupMember : groupMembers){
                Optional<LdapUser> userOptional = ldapService.lookupUser(LdapDn.builder().dn(groupMember).build());

                if(userOptional.isPresent()) {
                    LdapUser user = userOptional.get();

                    List<CheckInOutRecord> byDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc =
                            checkInOutRecordRepository
                                    .findByDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc(groupMember, fromAtStartOfDay, toAtEndOfDay);

                    Map<LocalDate, List<CheckInOutRecord>> dateMap = createDateMap(byDnIgnoreCaseAndTimestampIsBetweenOrderByTimestampDesc, fromAtStartOfDay, toAtEndOfDay);

                    List<UserDayReport> dayReports = new ArrayList<>();

                    dateMap.forEach((date, checkInOutRecords) -> {

                        Optional<UserEventRecord> eventRecordOptional = userEventRecordRepository.findByDnIgnoreCaseAndDate(groupMember, date)
                                .stream()
                                .filter(r -> r.getStatus() != UserStatusEnum.STANDARD)
                                .findFirst();

                        UserDayReport dayReport = UserDayReport.build(date, eventRecordOptional.orElse(null), checkInOutRecords);

                        dayReports.add(dayReport);
                    });

                    UserReport userReport = UserReport.createUserReport(user, dayReports);
                    userReports.add(userReport);
                }
            }

            groupReport.getUserReports().addAll(userReports);

            groupReport.getDayReports().addAll(createDayReportMap(userReports, from, to).values());

            Collections.sort(groupReport.getDayReports());

            return groupReport;
        }else{
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
