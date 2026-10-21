package com.winllc.innoutwork.data;

import com.winllc.innoutwork.model.UserRecord;
import lombok.*;

import java.time.format.DateTimeFormatter;

@Data
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileForm {
    private String notes;
    private String loginTime;
    /** An IANA time zone for the person's times in reports and exports; empty for their site's. */
    private String timeZone;

    public static ProfileForm buildFromRecord(UserRecord record) {
        ProfileForm form = new ProfileForm();
        form.setNotes(record.getNotes());
        form.setTimeZone(record.getTimeZone());
        if(record.getChosenLoginTime() != null) {
            form.setLoginTime(DateTimeFormatter.ISO_TIME.format(record.getChosenLoginTime()));
        }
        return form;
    }
}
