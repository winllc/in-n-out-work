package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class UserEventData {
    private String dn;
    private String date;
    private String fromDate;
    private String toDate;
    private String status;
}
