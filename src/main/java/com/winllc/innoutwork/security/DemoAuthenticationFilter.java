package com.winllc.innoutwork.security;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.model.UserRecord;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Signs every request in as the configured demo user, with every role.
 *
 * <p>Only ever added to the filter chain by {@link com.winllc.innoutwork.config.DemoSecurityConfig},
 * which exists only while {@code application.demo.enabled} is true. Nothing here is reachable
 * otherwise - there is no header, parameter or path that turns it on at runtime.
 *
 * <p>The identity comes from {@link AppUserDetailsService}, the same lookup a certificate or form
 * sign-in uses, so the demo sees the application as that directory entry would, then gains every
 * role on top so nothing is hidden. It grants no write access: the demo chain refuses every request
 * method that could change something, and that refusal, not this filter, is what makes the view
 * read-only.
 *
 * <p>The lookup happens once, not per request. It used to run on every request, which meant a DN the
 * directory could not resolve logged an LDAP failure on every page - the misses are not cached, so
 * nothing ever settled - and it also meant a directory round trip and a user-record query per
 * request once it could.
 */
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DemoAuthenticationFilter.class);

    /** How long to leave the directory alone after a failed lookup, so seeding later recovers. */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(1);

    private final AppUserDetailsService appUserDetailsService;
    private final String demoUserDn;
    private final Set<GrantedAuthority> authorities;

    /** The directory entry, once found. Set once and then no lookup happens again. */
    private volatile UserDetails directoryUser;

    /** Epoch millis before which no further lookup is attempted; set after a failure. */
    private volatile long quietUntil;

    public DemoAuthenticationFilter(AppUserDetailsService appUserDetailsService, String demoUserDn) {
        this.appUserDetailsService = appUserDetailsService;
        this.demoUserDn = demoUserDn;

        // Every role, so the demo shows the administrator's view rather than whatever the seeded
        // account happens to hold. Read-only is enforced by the chain, not by withholding roles.
        Set<GrantedAuthority> granted = new LinkedHashSet<>();
        for (UserRoleEnum role : UserRoleEnum.values()) {
            granted.add(new SimpleGrantedAuthority(role.name()));
        }
        this.authorities = Set.copyOf(granted);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(demoAuthentication());
            SecurityContextHolder.setContext(context);
        }

        filterChain.doFilter(request, response);
    }

    private Authentication demoAuthentication() {
        // The principal stays a UserDetails the rest of the application understands, and its
        // username is the DN - getName() is read as a DN all over the controllers.
        return new PreAuthenticatedAuthenticationToken(principal(), "N/A", List.copyOf(authorities));
    }

    private UserDetails principal() {
        UserDetails found = directoryUser;
        if (found != null) {
            return found;
        }

        if (System.currentTimeMillis() < quietUntil) {
            return unresolved();
        }

        try {
            UserDetails details = appUserDetailsService.loadUserByUsername(demoUserDn);

            // A miss does not throw: the service hands back a placeholder whose username is not the
            // DN asked for (and is not a DN at all), so compare rather than trust a non-null result.
            if (details != null && demoUserDn.equalsIgnoreCase(details.getUsername())) {
                directoryUser = details;
                log.info("Demo user {} resolved from the directory", demoUserDn);
                return details;
            }

            reportMissing(null);
        } catch (Exception e) {
            reportMissing(e);
        }

        quietUntil = System.currentTimeMillis() + RETRY_AFTER_FAILURE.toMillis();

        return unresolved();
    }

    /**
     * The principal used while the directory has no entry for the demo DN.
     *
     * <p>Carrying the configured DN matters: the application reads {@code getName()} and parses it
     * as a DN. {@link AppUserDetailsService}'s own miss value is not a DN, so passing that through
     * made every page that builds one from the principal throw as well as log the lookup failure.
     * With this, the demo still renders - thinner, since there is no directory entry behind it.
     */
    private UserDetails unresolved() {
        UserRecord record = new UserRecord();
        record.setDn(demoUserDn);

        return new AppUserDetails(record);
    }

    private void reportMissing(Exception cause) {
        // One line per retry window rather than a stack trace per page. Actionable, because the
        // usual cause is simply that the seeded demo account is not in this directory yet.
        log.error("Demo user {} could not be read from the directory, so the demo is running without "
                        + "a directory entry behind it. Check application.demo.user-dn names an entry that "
                        + "exists - test/seed-mock-data.sh creates the seeded one. Retrying in {}s.{}",
                demoUserDn, RETRY_AFTER_FAILURE.toSeconds(),
                cause == null ? " The directory returned no entry." : " " + cause);

        if (cause != null) {
            log.debug("Demo user lookup failed", cause);
        }
    }
}
