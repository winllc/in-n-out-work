package com.winllc.innoutwork.data;

import com.winllc.innoutwork.model.UserRecord;
import lombok.*;

@Data
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileForm {
    private String notes;

    public static ProfileForm buildFromRecord(UserRecord record) {
        ProfileForm form = new ProfileForm();
        form.setNotes(record.getNotes());
        return form;
    }
}
