package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.service.LdapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the Windows account from a verified Kerberos ticket ({@code jdoe@WINLLC.COM}) into the same user a
 * client certificate for that person would give: the directory entry is found by its account attribute, and
 * its DN is passed to {@link AppUserDetailsService}, so roles and the recorded DN are identical either way.
 * <p>
 * Not a Spring bean: a second UserDetailsService in the context would be picked up in places that expect
 * only the application's.
 */
public class WindowsAccountUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(WindowsAccountUserDetailsService.class);

    private final LdapService ldapService;
    private final AppUserDetailsService appUserDetailsService;
    private final ApplicationProperties.WindowsAuth settings;
    private final Set<String> allowedRealms;

    public WindowsAccountUserDetailsService(LdapService ldapService, AppUserDetailsService appUserDetailsService,
                                            ApplicationProperties.WindowsAuth settings) {
        this.ldapService = ldapService;
        this.appUserDetailsService = appUserDetailsService;
        this.settings = settings;
        this.allowedRealms = allowedRealms(settings);
    }

    /** The configured realms, or else the service principal's realm; upper-cased. Empty allows any realm. */
    static Set<String> allowedRealms(ApplicationProperties.WindowsAuth settings) {
        List<String> configured = settings.getAllowedRealms() == null ? List.of() : settings.getAllowedRealms();
        Set<String> realms = configured.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!realms.isEmpty()) {
            return realms;
        }
        String principal = settings.getServicePrincipal();
        int at = principal == null ? -1 : principal.lastIndexOf('@');
        return at < 0 ? Set.of() : Set.of(principal.substring(at + 1).toUpperCase(Locale.ROOT));
    }

    @Override
    public UserDetails loadUserByUsername(String principal) throws UsernameNotFoundException {
        if (principal == null || principal.isBlank()) {
            throw new UsernameNotFoundException("No Windows account in the ticket");
        }

        int at = principal.lastIndexOf('@');
        String account = at < 0 ? principal : principal.substring(0, at);
        String realm = at < 0 ? null : principal.substring(at + 1).toUpperCase(Locale.ROOT);

        // Service and host principals (HTTP/server, host/pc) are not people.
        if (account.isBlank() || account.contains("/")) {
            throw rejected(principal, "not a user account");
        }
        if (!allowedRealms.isEmpty() && (realm == null || !allowedRealms.contains(realm))) {
            throw rejected(principal, "realm not allowed (allowed: " + allowedRealms + ")");
        }

        String value = settings.isStripRealm() ? account : principal;
        LdapUser user = ldapService.lookupUniqueUser(settings.getAccountAttribute(), value)
                .orElseThrow(() -> rejected(principal,
                        "no single directory user has " + settings.getAccountAttribute() + "=" + value));

        UserDetails details = appUserDetailsService.loadUserByUsername(user.getDn());
        if ("NOTFOUND".equals(details.getUsername())) {
            throw rejected(principal, "directory user " + user.getDn() + " could not be loaded");
        }

        log.debug("Windows account {} signed in as {}", principal, user.getDn());
        return details;
    }

    private static UsernameNotFoundException rejected(String principal, String reason) {
        // A rejected ticket is otherwise just a 401 on the client, so say why here.
        log.warn("Windows sign-in refused for {}: {}", principal, reason);
        return new UsernameNotFoundException("Windows account not accepted: " + reason);
    }
}
