package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class CheckInOut {
    private String windowsUserId;
    private String sessionId;
}
