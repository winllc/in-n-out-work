package com.winllc.innoutwork.data;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    private boolean locked;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
    private ZonedDateTime checkedInAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
    private ZonedDateTime checkedOutAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm a z")
    private ZonedDateTime lastStatusChangeAt;
    private String notes;
    private String status;

}
