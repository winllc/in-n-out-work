package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class PermissionUpdate {
    private String userDn;
    private String groupDn;
    private boolean selected;
}
