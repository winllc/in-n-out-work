package com.winllc.innoutwork.data;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.winllc.innoutwork.constant.DateTimeConstants;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

import java.time.ZonedDateTime;

@Data
@ToString
@Builder
public class UserStatus {
    private String dn;
    private String cn;
    private String status;
    //private boolean checkedIn;
    //private boolean locked;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeConstants.DATE_TIME_FORMAT)
    private ZonedDateTime checkedInAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeConstants.DATE_TIME_FORMAT)
    private ZonedDateTime checkedOutAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeConstants.DATE_TIME_FORMAT)
    private ZonedDateTime lastStatusChangeAt;
    private String notes;

    public String getCn(){
        if(dn != null){
            String[] parts = dn.split(",");
            if(parts.length > 0){
                return parts[0].trim().replace("cn=", "").replace("CN=", "");
            }
        }
        return "";
    }
}
