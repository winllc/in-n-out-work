package com.winllc.innoutwork.cron;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
public class MarkInactiveCron {

    private final CheckInOutRecordRepository checkInOutRecordRepository;
    private final ApplicationProperties properties;

    public MarkInactiveCron(CheckInOutRecordRepository checkInOutRecordRepository, ApplicationProperties properties) {
        this.checkInOutRecordRepository = checkInOutRecordRepository;
        this.properties = properties;
    }

    @Async
    @Scheduled(fixedDelayString = "#{@inactiveCronProperties.fixedRate}",
            initialDelayString = "#{@inactiveCronProperties.initialDelay}")
    public void run(){

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime startOfDay = ZonedDateTime.now().toLocalDate().atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime endOfDay = startOfDay.plusDays(1).minusNanos(1);

        checkInOutRecordRepository.findLatestRecordsByDn(CheckInOutEnum.LOCK, startOfDay, endOfDay)
                .forEach(record -> {
                    ZonedDateTime timestampPlus = record.getTimestamp().plusMinutes(properties.getCheckOutAfterMinutes());

                    if(timestampPlus.isBefore(now)){
                        CheckInOutRecord checkInOutRecord = new CheckInOutRecord();
                        checkInOutRecord.setDn(record.getDn());
                        checkInOutRecord.setTimestamp(timestampPlus);
                        checkInOutRecord.setAction(CheckInOutEnum.CHECK_OUT);
                        checkInOutRecord.setLocation(record.getLocation());
                        checkInOutRecord.setBranch(record.getBranch());
                        checkInOutRecord.setEmployeeType(record.getEmployeeType());
                        checkInOutRecord.setOrganization(record.getOrganization());
                        checkInOutRecord.setSessionId(record.getSessionId());
                        checkInOutRecordRepository.save(checkInOutRecord);
                    }
                });
    }
}
