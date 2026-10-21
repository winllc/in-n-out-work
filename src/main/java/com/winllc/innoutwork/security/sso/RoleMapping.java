package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.constant.UserRoleEnum;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ENS_SSO_ROLE_MAPPING: which of the identity provider's groups grant which ReadyRoom role. Nothing else an IdP
 * says grants a role: a group not listed here grants nothing, whatever it is called.
 * <p>
 * {@code group=ROLE} pairs separated by ; or new lines. The last {@code =} separates the role, so a DN-style group
 * ({@code CN=ReadyRoom Admins,OU=Groups,DC=example,DC=com=ADMIN}) works. Group names are matched ignoring case;
 * Entra ID sends group object ids unless configured otherwise. Roles: MANAGER or ADMIN.
 */
public final class RoleMapping {

    private static final Set<UserRoleEnum> GRANTABLE = EnumSet.of(UserRoleEnum.MANAGER, UserRoleEnum.ADMIN);

    private final Map<String, Set<UserRoleEnum>> byGroup;

    private RoleMapping(Map<String, Set<UserRoleEnum>> byGroup) {
        this.byGroup = byGroup;
    }

    public static RoleMapping parse(String configured) {
        Map<String, Set<UserRoleEnum>> byGroup = new LinkedHashMap<>();
        if (configured != null) {
            for (String pair : configured.split("[;\\n]")) {
                String entry = pair.strip();
                if (entry.isEmpty()) {
                    continue;
                }
                int eq = entry.lastIndexOf('=');
                if (eq <= 0 || eq == entry.length() - 1) {
                    throw new IllegalStateException("ENS_SSO_ROLE_MAPPING: \"" + entry + "\" is not group=ROLE");
                }
                String group = entry.substring(0, eq).strip().toLowerCase(Locale.ROOT);
                String roleName = entry.substring(eq + 1).strip().toUpperCase(Locale.ROOT);
                UserRoleEnum role;
                try {
                    role = UserRoleEnum.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    role = null;
                }
                if (role == null || !GRANTABLE.contains(role)) {
                    throw new IllegalStateException("ENS_SSO_ROLE_MAPPING: \"" + roleName + "\" for group \""
                            + entry.substring(0, eq).strip() + "\" is not a role an IdP group can grant; use "
                            + GRANTABLE);
                }
                byGroup.computeIfAbsent(group, g -> EnumSet.noneOf(UserRoleEnum.class)).add(role);
            }
        }
        return new RoleMapping(byGroup);
    }

    public boolean isEmpty() {
        return byGroup.isEmpty();
    }

    /** The roles {@code groups} grant. */
    public Set<UserRoleEnum> rolesFor(Collection<String> groups) {
        Set<UserRoleEnum> roles = EnumSet.noneOf(UserRoleEnum.class);
        if (groups != null) {
            for (String group : groups) {
                if (group != null) {
                    roles.addAll(byGroup.getOrDefault(group.strip().toLowerCase(Locale.ROOT), Set.of()));
                }
            }
        }
        return roles;
    }

    int size() {
        return byGroup.size();
    }
}
