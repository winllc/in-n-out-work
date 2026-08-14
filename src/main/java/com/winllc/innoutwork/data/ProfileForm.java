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

    public static ProfileForm buildFromRecord(UserRecord record) {
        ProfileForm form = new ProfileForm();
        form.setNotes(record.getNotes());
        if(record.getChosenLoginTime() != null) {
            form.setLoginTime(DateTimeFormatter.ISO_TIME.format(record.getChosenLoginTime()));
        }
        return form;
    }
}
