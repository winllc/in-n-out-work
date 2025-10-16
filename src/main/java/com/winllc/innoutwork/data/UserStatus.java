package com.winllc.innoutwork.data;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.time.ZonedDateTime;

@Data
@ToString
@Builder
public class UserStatus {
    private String dn;
    private boolean checkedIn;
    private ZonedDateTime checkedInAt;
    private ZonedDateTime checkedOutAt;
    private String notes;

}
