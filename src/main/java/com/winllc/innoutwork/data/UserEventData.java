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
    private String lateArrivalTime;
    /** Optional, for a status that is requested: shown to the approvers. */
    private String note;
}
