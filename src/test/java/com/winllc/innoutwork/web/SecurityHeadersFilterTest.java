package com.winllc.innoutwork.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHeadersFilterTest {

    private static MockHttpServletResponse filter(SecurityHeadersFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void everyResponseGetsThePolicyWithThisRequestsNonce() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/home");

        MockHttpServletResponse response = filter(new SecurityHeadersFilter(false), request);

        String nonce = (String) request.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE);
        assertNotNull(nonce);
        assertEquals("default-src 'self'; script-src 'self' 'nonce-" + nonce + "'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; "
                        + "base-uri 'self'; form-action 'self'; object-src 'none'",
                response.getHeader("Content-Security-Policy"));
        assertNull(response.getHeader("Content-Security-Policy-Report-Only"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("strict-origin-when-cross-origin", response.getHeader("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=(), payment=()", response.getHeader("Permissions-Policy"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("script-src 'self' 'nonce-"));
        assertTrue(!response.getHeader("Content-Security-Policy").contains("unsafe-eval")
                && !response.getHeader("Content-Security-Policy").contains("script-src 'self' 'unsafe-inline'"));
    }

    @Test
    void eachRequestHasItsOwnNonce() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(false);
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/");
        filter(filter, first);
        filter(filter, second);

        assertNotEquals(first.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE),
                second.getAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE));
    }

    @Test
    void reportOnlySendsTheReportOnlyHeaderInstead() throws Exception {
        MockHttpServletResponse response = filter(new SecurityHeadersFilter(true), new MockHttpServletRequest("GET", "/"));

        assertNull(response.getHeader("Content-Security-Policy"));
        assertTrue(response.getHeader("Content-Security-Policy-Report-Only").startsWith("default-src 'self'"));
    }

    @Test
    void strictTransportSecurityOnlyOnSecureRequests() throws Exception {
        MockHttpServletRequest plain = new MockHttpServletRequest("GET", "/");
        MockHttpServletRequest secure = new MockHttpServletRequest("GET", "/");
        secure.setSecure(true);

        assertNull(filter(new SecurityHeadersFilter(false), plain).getHeader("Strict-Transport-Security"));
        assertEquals("max-age=31536000; includeSubDomains",
                filter(new SecurityHeadersFilter(false), secure).getHeader("Strict-Transport-Security"));
    }

    /** An error page rendered for the same request keeps the nonce its policy names. */
    @Test
    void theErrorDispatchKeepsTheNonce() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setAttribute(SecurityHeadersFilter.NONCE_ATTRIBUTE, "abc");

        MockHttpServletResponse response = filter(new SecurityHeadersFilter(false), request);

        assertTrue(response.getHeader("Content-Security-Policy").contains("'nonce-abc'"));
    }
}
