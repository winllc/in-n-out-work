package com.winllc.innoutwork.data.reports;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import lombok.Getter;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Getter
public class UserDayReport implements Comparable<UserDayReport> {
    private LocalDate day;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
    private ZonedDateTime checkInTime;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
    private ZonedDateTime checkOutTime;
    private String status;

    public static UserDayReport build(LocalDate date, UserEventRecord userEventRecord, List<CheckInOutRecord> records){
        UserDayReport dayReport = new UserDayReport();
        dayReport.day = date;
        if(userEventRecord != null){
            dayReport.status = userEventRecord.getStatus().getFriendlyName();
        }else{
            dayReport.status = UserStatusEnum.STANDARD.getFriendlyName();
        }

        if(!CollectionUtils.isEmpty(records)){
            Optional<CheckInOutRecord> checkInTime = getCheckInTime(records);
            if(checkInTime.isPresent()){
                dayReport.checkInTime = checkInTime.get().getTimestamp();
            }

            Optional<CheckInOutRecord> checkOutTime = getCheckOutTime(records);
            if(checkOutTime.isPresent()){
                dayReport.checkOutTime = checkOutTime.get().getTimestamp();
            }
        }

        return dayReport;
    }

    public boolean isNeverCheckedOut(){
        return this.checkInTime != null && this.checkOutTime == null;
    }

    public int getCheckedInMinutes(){
        if(this.checkInTime != null && this.checkOutTime != null){
            long minutes = java.time.Duration.between(this.checkInTime, this.checkOutTime).toMinutes();
            return (int) minutes;
        }
        return 0;
    }

    public String getCheckedInPeriod(){
        if(this.checkInTime == null || this.checkOutTime == null){
            return "N/A";
        }else {
            Duration between = Duration.between(this.checkInTime, this.checkOutTime);
            long hours = between.toHours();
            long remainingMinutes = between.minusHours(hours).toMinutes();
            return String.format("%d hrs, %d mins", hours, remainingMinutes);
        }
    }

    private static Optional<CheckInOutRecord> getCheckOutTime(List<CheckInOutRecord> records){
        if(records.isEmpty()){
            return Optional.empty();
        }else{
            return records.stream()
                    .filter(r -> r.getAction() == CheckInOutEnum.CHECK_OUT)
                    .sorted()
                    .findFirst();
        }
    }

    private static Optional<CheckInOutRecord> getCheckInTime(List<CheckInOutRecord> records){
        if(records.isEmpty()){
            return Optional.empty();
        }else{
            return records.stream()
                    .filter(r -> r.getAction() == CheckInOutEnum.CHECK_IN)
                    .sorted(Comparator.reverseOrder())
                    .findFirst();
        }
    }

    public String getFormatedDay(){
        return java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy").format(this.day);
    }

    @Override
    public int compareTo(UserDayReport o) {
        return this.day.compareTo(o.day);
    }
}
