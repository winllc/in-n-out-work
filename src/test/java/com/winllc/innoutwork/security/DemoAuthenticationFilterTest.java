package com.winllc.innoutwork.security;

import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.AppUserDetails;
import com.winllc.innoutwork.model.UserRecord;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The filter that stands in for signing in while demo mode is on. It decides who the demo is, and
 * nothing else - what the demo may do is decided by the chain in DemoSecurityConfig.
 */
@ExtendWith(MockitoExtension.class)
class DemoAuthenticationFilterTest {

    private static final String DEMO_DN = "cn=Demo User,ou=Users,dc=winllc,dc=com";

    @Mock
    private AppUserDetailsService appUserDetailsService;

    @Mock
    private FilterChain chain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private DemoAuthenticationFilter filter() {
        return new DemoAuthenticationFilter(appUserDetailsService, DEMO_DN);
    }

    private void stubDemoUser() {
        UserRecord record = new UserRecord();
        record.setDn(DEMO_DN);
        AppUserDetails details = new AppUserDetails(record);
        details.addAuthority(UserRoleEnum.USER.name());

        when(appUserDetailsService.loadUserByUsername(DEMO_DN)).thenReturn(details);
    }

    private Set<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Test
    void anUnauthenticatedRequestIsServedAsTheConfiguredDemoUser() throws Exception {
        stubDemoUser();

        filter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        // Controllers read the DN off getName(), so it has to be the DN and not a display name.
        assertEquals(DEMO_DN, authentication.getName());
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void theDemoHoldsEveryRoleSoNothingIsHiddenFromTheView() throws Exception {
        stubDemoUser();

        filter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        Set<String> granted = authorities(SecurityContextHolder.getContext().getAuthentication());
        for (UserRoleEnum role : UserRoleEnum.values()) {
            assertTrue(granted.contains(role.name()), "demo view is missing " + role);
        }
    }

    @Test
    void anExistingAuthenticationIsLeftAlone() throws Exception {
        Authentication existing = new TestingAuthenticationToken("someone", "creds", "ADMIN");
        SecurityContextHolder.getContext().setAuthentication(existing);

        filter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertSame(existing, SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(appUserDetailsService);
    }
}
