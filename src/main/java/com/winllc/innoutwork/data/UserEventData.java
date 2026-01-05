package com.winllc.innoutwork.data;

import lombok.Data;

@Data
public class UserEventData {
    private String dn;
    private String date;
    private String fromDate;
    private String toDate;
    private String status;
}
