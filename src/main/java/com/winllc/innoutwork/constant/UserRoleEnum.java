package com.winllc.innoutwork.constant;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

@Getter
public enum UserRoleEnum {
    USER(true),
    MANAGER(true),
    ADMIN(false);

    private final boolean visible;

    UserRoleEnum(boolean visible) {
        this.visible = visible;
    }


    public static List<UserRoleEnum> getVisibleRoles(){
        return Stream.of(values())
                .filter(v -> v.visible)
                .toList();
    }
}
