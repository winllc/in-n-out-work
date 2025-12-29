package com.winllc.innoutwork.constant;

import lombok.Getter;

public enum UserStatusEnum {
    STANDARD("normal"),
    OUT_OF_OFFICE("Out of Office"),
    WORK_FROM_HOME("Work From Home"),
    TDY("TDY"),
    SCHEDULED_LEAVE("Scheduled Leave"),;

    @Getter
    private final String friendlyName;

   UserStatusEnum(String friendlyName) {
       this.friendlyName = friendlyName;
   }
}
