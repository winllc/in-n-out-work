package com.winllc.innoutwork.constant;

import lombok.Getter;

public enum UserStatusEnum {
    STANDARD("normal", true, true),
    OUT_OF_OFFICE("Out of Office", true, true),
    WORK_FROM_HOME("Work From Home",true, true),
    TDY("TDY", true, true),
    SCHEDULED_LEAVE("Scheduled Leave", true, true),
    UNSCHEDULED_LEAVE("Unscheduled Leave", false, false),
    ABSENT_EXCUSED("Absent Excused", false, true),
    ABSENT_UNEXCUSED("Absent Unexcused", false, false),;

    @Getter
    private final String friendlyName;
    @Getter
    private final boolean selectable;
    @Getter
    private final boolean excusable;

   UserStatusEnum(String friendlyName, boolean selectable, boolean excusable) {
       this.friendlyName = friendlyName;
       this.selectable = selectable;
       this.excusable = excusable;
   }
}
