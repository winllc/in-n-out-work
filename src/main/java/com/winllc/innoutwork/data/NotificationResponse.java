package com.winllc.innoutwork.data;

import lombok.Data;

@Data
public class NotificationResponse {
    private Long notificationId;
    private String response;
    private String responseTimestamp;
    private String responderDn;
}
