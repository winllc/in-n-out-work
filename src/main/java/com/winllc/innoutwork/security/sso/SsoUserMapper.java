package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps whoever an identity provider signed in to one directory user, the way certificate sign-in does with
 * ENS_X509_USER_FILTER: the protocol's user filter, with the IdP's claims or attributes put in its placeholders
 * (escaped), must match exactly one directory user. That user then gets the roles any other sign-in gives them
 * (AppUserDetailsService: USER, an administrator-assigned role, ADMIN for super-user DNs), plus the roles
 * ENS_SSO_ROLE_MAPPING grants for the IdP's groups. Nothing else the IdP asserts grants anything.
 */
public class SsoUserMapper {

    private static final Logger log = LoggerFactory.getLogger(SsoUserMapper.class);

    /** {name}: a claim or attribute name, which may be a URI. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}\\s]+)}");

    private final AppUserDetailsService users;
    private final LdapService ldap;
    private final RoleMapping roles;

    public SsoUserMapper(AppUserDetailsService users, LdapService ldap, RoleMapping roles) {
        this.users = users;
        this.ldap = ldap;
        this.roles = roles;
    }

    /** Fails startup on a filter with no placeholder, which would sign everyone in as the same person. */
    public static void requireTemplate(String setting, String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException(setting + " is required: an LDAP filter finding the directory user,"
                    + " for example (userPrincipalName={preferred_username}); use a claim the identity provider verifies");
        }
        if (!PLACEHOLDER.matcher(template).find()) {
            throw new IllegalStateException(setting + " has no {placeholder}: it would match the same directory"
                    + " user for everyone");
        }
    }

    /**
     * @param values the IdP's claims or attributes, by name
     * @param groups the IdP's groups for the role mapping
     * @throws SsoRefusedException when no single directory user matches, or the user cannot sign in
     */
    public AppUserDetails map(String template, Map<String, List<String>> values, Collection<String> groups) {
        StringBuilder filter = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String value = first(values, m.group(1));
            if (value == null || value.isBlank()) {
                throw new SsoRefusedException("the identity provider sent no " + m.group(1));
            }
            m.appendReplacement(filter, Matcher.quoteReplacement(LdapService.escapeLdapFilter(value)));
        }
        m.appendTail(filter);
        LdapUser user = ldap.lookupUniqueUserByFilter(filter.toString())
                .orElseThrow(() -> new SsoRefusedException("no single directory user matches " + filter));

        UserDetails details;
        try {
            details = users.loadUserByUsername(user.getDn());
        } catch (UsernameNotFoundException e) {
            throw new SsoRefusedException("directory user could not be loaded");
        }
        if (!(details instanceof AppUserDetails app)) {
            throw new SsoRefusedException("directory user could not be loaded");
        }
        Set<UserRoleEnum> granted = roles.rolesFor(groups);
        for (UserRoleEnum role : granted) {
            boolean has = app.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(role.name()::equals);
            if (!has) {
                app.addAuthority(role.name());
            }
        }
        if (!granted.isEmpty()) {
            log.debug("Single sign-on: {} granted {} by their identity provider's groups", user.getDn(), granted);
        }
        return app;
    }

    /** The first value of {@code name}, matched exactly, else ignoring case. */
    static String first(Map<String, List<String>> values, String name) {
        List<String> found = values.get(name);
        if (found == null) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                    found = entry.getValue();
                    break;
                }
            }
        }
        return found == null || found.isEmpty() ? null : found.get(0);
    }
}
