package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.model.SsoSamlRequest;
import com.winllc.innoutwork.repository.SsoSamlRequestRepository;
import com.winllc.innoutwork.security.ConsentBanner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2RedirectAuthenticationRequest;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SAML requests kept in the database, found by RelayState, tied to the browser that started them, used once. */
class SamlRequestStoreTest {

    private final Map<String, SsoSamlRequest> rows = new HashMap<>();
    private final SsoSamlRequestRepository repository = mock(SsoSamlRequestRepository.class);
    private Instant now = Instant.parse("2026-10-18T09:00:00Z");
    private SamlRequestStore store;

    @BeforeEach
    void setUp() {
        when(repository.save(any())).thenAnswer(inv -> {
            SsoSamlRequest row = inv.getArgument(0);
            rows.put(row.getRelayState(), row);
            return row;
        });
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(repository.deleteByRelayState(anyString())).thenAnswer(inv -> rows.remove(inv.getArgument(0)) == null ? 0 : 1);
        store = new SamlRequestStore(repository, new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        });
    }

    private static AbstractSaml2AuthenticationRequest request(String relayState) {
        RelyingPartyRegistration registration = RelyingPartyRegistration.withRegistrationId("saml")
                .entityId("https://rr.example.com/sp").assertionConsumerServiceLocation("https://rr.example.com/acs")
                .assertingPartyMetadata(p -> p.entityId("https://idp.example.com").singleSignOnServiceLocation(
                        "https://idp.example.com/sso").singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT))
                .build();
        return Saml2RedirectAuthenticationRequest.withRelyingPartyRegistration(registration).samlRequest("req")
                .authenticationRequestUri("https://idp.example.com/sso").relayState(relayState).id("ARQ1").build();
    }

    /** Starts a sign-in; returns the cookie the browser was given. */
    private Cookie start(String relayState, boolean consented) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/saml2/authenticate/saml");
        request.setSecure(true);
        if (consented) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute(ConsentBanner.ACKNOWLEDGED, Instant.now());
            request.setSession(session);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        store.saveAuthenticationRequest(request(relayState), request, response);
        String header = response.getHeader("Set-Cookie");
        assertTrue(header.contains("SameSite=None") && header.contains("Secure") && header.contains("HttpOnly")
                && header.contains("Path=/login/saml2/sso/"), header);
        String pair = header.substring(0, header.indexOf(';'));
        return new Cookie(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
    }

    private static MockHttpServletRequest answer(String relayState, Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/saml2/sso/saml");
        request.setParameter("RelayState", relayState);
        if (cookies.length > 0) {
            request.setCookies(cookies);
        }
        return request;
    }

    @Test
    void theAnswerFindsTheRequestOnceFromTheBrowserThatStartedIt() {
        String relayState = UUID.randomUUID().toString();
        Cookie cookie = start(relayState, true);

        assertEquals("ARQ1", store.loadAuthenticationRequest(answer(relayState, cookie)).getId());
        MockHttpServletRequest first = answer(relayState, cookie);
        assertNotNull(store.removeAuthenticationRequest(first, new MockHttpServletResponse()));
        assertEquals(Boolean.TRUE, first.getAttribute(SamlRequestStore.CONSENTED));

        assertNull(store.loadAuthenticationRequest(answer(relayState, cookie)), "used once");
        assertNull(store.removeAuthenticationRequest(answer(relayState, cookie), new MockHttpServletResponse()));
    }

    @Test
    void anotherBrowserFindsNothing() {
        String relayState = UUID.randomUUID().toString();
        Cookie cookie = start(relayState, false);

        assertNull(store.loadAuthenticationRequest(answer(relayState)), "no cookie: an answer posted from elsewhere");
        assertNull(store.loadAuthenticationRequest(answer(relayState, new Cookie(cookie.getName(), "forged"))));
        assertNotNull(store.loadAuthenticationRequest(answer(relayState, cookie)));
    }

    @Test
    void anExpiredRequestIsNotFound() {
        String relayState = UUID.randomUUID().toString();
        Cookie cookie = start(relayState, false);
        now = now.plus(SamlRequestStore.LIFETIME).plusSeconds(1);

        assertNull(store.loadAuthenticationRequest(answer(relayState, cookie)));
    }

    @Test
    void noConsentIsCarriedOverWhenTheStartingSessionHadNotGivenIt() {
        String relayState = UUID.randomUUID().toString();
        Cookie cookie = start(relayState, false);
        MockHttpServletRequest answer = answer(relayState, cookie);

        assertNotNull(store.removeAuthenticationRequest(answer, new MockHttpServletResponse()));
        assertNull(answer.getAttribute(SamlRequestStore.CONSENTED));
    }
}
