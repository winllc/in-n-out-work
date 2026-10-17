package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class CheckInOut {
    private String windowsUserId;
    /** The session id the check-in response returned; a credential, so kept out of the log. */
    @ToString.Exclude
    private String sessionId;
}
