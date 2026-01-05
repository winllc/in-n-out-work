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
    private String status;

    public static ProfileForm buildFromRecord(UserRecord record) {
        ProfileForm form = new ProfileForm();
        form.setNotes(record.getNotes());
        if(record.getStatus() != null) {
            form.setStatus(record.getStatus().name());
        }
        return form;
    }
}
