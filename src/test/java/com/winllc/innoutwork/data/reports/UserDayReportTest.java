package com.winllc.innoutwork.data.reports;

import com.winllc.innoutwork.constant.CheckInOutEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserEventRecord;
import org.apache.catalina.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserDayReportTest {

    @Test
    void build() {
        LocalDate now = LocalDate.now();
        UserEventRecord userEventRecord = new UserEventRecord();
        userEventRecord.setStatus(UserStatusEnum.STANDARD);

        List<CheckInOutRecord> checkInOutRecords = new ArrayList<>();
        CheckInOutRecord in = new CheckInOutRecord();
        in.setAction(CheckInOutEnum.CHECK_IN);
        in.setTimestamp(ZonedDateTime.now().minusHours(9));

        CheckInOutRecord lock1 = new CheckInOutRecord();
        lock1.setAction(CheckInOutEnum.LOCK);
        lock1.setTimestamp(ZonedDateTime.now().minusHours(6));

        CheckInOutRecord unlock = new CheckInOutRecord();
        unlock.setAction(CheckInOutEnum.UNLOCK);
        unlock.setTimestamp(ZonedDateTime.now().minusHours(5));

        CheckInOutRecord lock2 = new CheckInOutRecord();
        lock2.setAction(CheckInOutEnum.LOCK);
        lock2.setTimestamp(ZonedDateTime.now().minusHours(4));

        CheckInOutRecord out = new CheckInOutRecord();
        out.setForced(true);
        out.setAction(CheckInOutEnum.CHECK_OUT);
        out.setTimestamp(ZonedDateTime.now().minusHours(1));

        checkInOutRecords.add(in);
        checkInOutRecords.add(lock1);
        checkInOutRecords.add(unlock);
        checkInOutRecords.add(lock2);
        checkInOutRecords.add(out);


        UserDayReport userDayReport = UserDayReport.build(now, userEventRecord, checkInOutRecords);

        ZonedDateTime checkOutTime = userDayReport.getCheckOutTime();

        assertEquals(lock2.getTimestamp(), checkOutTime);
    }
}