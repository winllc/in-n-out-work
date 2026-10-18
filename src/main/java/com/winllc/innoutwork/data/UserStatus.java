package com.winllc.innoutwork.data;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.winllc.innoutwork.constant.DateTimeConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@ToString
@Builder
// @Builder on its own would leave this class with only an all-args constructor, which makes
// Spring and Jackson fall back to constructor binding and turns any unsupplied primitive into a
// conversion failure. The explicit pair below keeps a no-args constructor available.
@NoArgsConstructor
@AllArgsConstructor
public class UserStatus {
    private String dn;
    private String cn;
    private String status;
    private List<LdapGroup> memberOf = new ArrayList<>();
    private String role;
    //private boolean checkedIn;
    //private boolean locked;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeConstants.DATE_TIME_FORMAT)
    private ZonedDateTime checkedInAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeConstants.DATE_TIME_FORMAT)
    private ZonedDateTime checkedOutAt;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeConstants.DATE_TIME_FORMAT)
    private ZonedDateTime lastStatusChangeAt;
    private String notes;
    private String organization;
    private String employeeType;
    private String location;
    private String branch;
    private String email;
    private String phoneNumber;
    private String managerDn;
    private String averageLoginTime;
    /**
     * The day's presence from the check-in records alone (IN, AWAY, OUT or NONE), before a calendar status replaces
     * it in {@link #status}. Not sent anywhere: AccessPolicy reads it for coworkers who see only in or out.
     */
    @JsonIgnore
    @ToString.Exclude
    private String presence;

    public String getCn(){
        return LdapDn.cnOf(dn);
    }
}
