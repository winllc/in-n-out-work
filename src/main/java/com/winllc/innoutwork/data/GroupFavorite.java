package com.winllc.innoutwork.data;

import lombok.Data;
import lombok.ToString;

@Data
@ToString
public class GroupFavorite {
    private String groupDn;
    private boolean selected;
}
