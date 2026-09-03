package com.winllc.innoutwork.data;

import lombok.*;

/**
 * Command object behind the role selector on the user details page.
 *
 * <p>Deliberately narrow: the page renders a {@link UserStatus}, but binding that read model
 * to the POST would let a request set anything on it - notes, organization, status - simply
 * by adding parameters. Only the two fields the form actually submits live here, so
 * everything else in the request is ignored by data binding.
 *
 * <p>The role is carried as a String rather than a {@code UserRoleEnum} so an unrecognised
 * value fails the controller's own check instead of a binding error; the controller
 * validates it against the enum before applying it.
 */
@Data
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleUpdateForm {

    /** DN of the user whose role is being changed. */
    private String dn;

    /** Name of a {@link com.winllc.innoutwork.constant.UserRoleEnum} value. */
    private String role;

    /** Seeds the form from the user being displayed, so the selector opens on their current role. */
    public static RoleUpdateForm buildFromStatus(UserStatus status) {
        RoleUpdateForm form = new RoleUpdateForm();
        if (status != null) {
            form.setDn(status.getDn());
            form.setRole(status.getRole());
        }
        return form;
    }
}
