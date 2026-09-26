package com.winllc.innoutwork.security;

import com.winllc.innoutwork.constant.UserRoleEnum;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Signs every request in as the configured demo user, with every role.
 *
 * <p>Only ever added to the filter chain by {@link com.winllc.innoutwork.config.DemoSecurityConfig},
 * which exists only while {@code application.demo.enabled} is true. Nothing here is reachable
 * otherwise - there is no header, parameter or path that turns it on at runtime.
 *
 * <p>The identity comes from {@link AppUserDetailsService}, the same lookup a certificate or form
 * sign-in uses, so the demo sees the application exactly as that directory entry would, then gains
 * every role on top so nothing is hidden. It grants no write access: the demo chain refuses every
 * request method that could change something, and that refusal, not this filter, is what makes the
 * view read-only.
 */
public class DemoAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DemoAuthenticationFilter.class);

    private final AppUserDetailsService appUserDetailsService;
    private final String demoUserDn;

    public DemoAuthenticationFilter(AppUserDetailsService appUserDetailsService, String demoUserDn) {
        this.appUserDetailsService = appUserDetailsService;
        this.demoUserDn = demoUserDn;
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
        UserDetails details = appUserDetailsService.loadUserByUsername(demoUserDn);

        // Every role, so the demo shows the administrator's view rather than whatever the seeded
        // account happens to hold. Read-only is enforced by the chain, not by withholding roles.
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(details.getAuthorities());
        for (UserRoleEnum role : UserRoleEnum.values()) {
            authorities.add(new SimpleGrantedAuthority(role.name()));
        }

        log.debug("Serving request as demo user {} with {}", demoUserDn, authorities);

        // The principal stays the UserDetails the rest of the application expects; only the
        // authorities are widened. getName() is the DN, as it is for a certificate sign-in.
        return new PreAuthenticatedAuthenticationToken(details, "N/A", authorities);
    }
}
