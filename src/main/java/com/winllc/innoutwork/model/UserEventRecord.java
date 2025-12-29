package com.winllc.innoutwork.model;

import com.winllc.innoutwork.constant.UserStatusEnum;

import java.time.ZonedDateTime;

public class UserEventRecord {
    private ZonedDateTime date;
    private UserStatusEnum status;
    private UserRecord userRecord;
}
