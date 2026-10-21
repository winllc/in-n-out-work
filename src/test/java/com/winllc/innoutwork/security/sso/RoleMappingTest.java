package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.constant.UserRoleEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IdP groups grant roles only as ENS_SSO_ROLE_MAPPING says. */
class RoleMappingTest {

    @Test
    void onlyListedGroupsGrantRoles() {
        RoleMapping mapping = RoleMapping.parse("ReadyRoom Admins=ADMIN; ReadyRoom Managers=manager\n"
                + "CN=Leads,OU=Groups,DC=example,DC=com=MANAGER");

        assertEquals(Set.of(UserRoleEnum.ADMIN), mapping.rolesFor(List.of("readyroom admins")));
        assertEquals(Set.of(UserRoleEnum.MANAGER), mapping.rolesFor(List.of("CN=Leads,OU=Groups,DC=example,DC=com")));
        assertTrue(mapping.rolesFor(List.of("Domain Admins", "ADMIN", "admin")).isEmpty(),
                "a group named like a role grants nothing");
        assertTrue(RoleMapping.parse("").rolesFor(List.of("ReadyRoom Admins")).isEmpty());
    }

    @Test
    void anUnknownOrUngrantableRoleStopsStartup() {
        assertThrows(IllegalStateException.class, () -> RoleMapping.parse("Admins=ROOT"));
        assertThrows(IllegalStateException.class, () -> RoleMapping.parse("Everyone=USER"));
        assertThrows(IllegalStateException.class, () -> RoleMapping.parse("Admins"));
        assertThrows(IllegalStateException.class, () -> RoleMapping.parse("=ADMIN"));
    }
}
