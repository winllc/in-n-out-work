package com.winllc.innoutwork.security.sso;

import com.winllc.innoutwork.model.SsoSamlRequest;
import com.winllc.innoutwork.repository.SsoSamlRequestRepository;
import com.winllc.innoutwork.security.ConsentBanner;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.saml2.core.Saml2ParameterNames;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The SAML sign-in requests ReadyRoom sent, kept in the database until the identity provider answers, instead of
 * in the browser session as Spring Security does by default. The answer is a cross-site POST, on which browsers
 * leave out the session cookie (SameSite=Lax), and it may reach any replica; so a request is found by the
 * RelayState the IdP sends back with it.
 * <p>
 * Each request is tied to the browser that started it by a cookie of its own ({@code ENS_SAML_<relay state>},
 * SameSite=None, Secure over HTTPS, HttpOnly, only sent to the assertion consumer service, ten minutes): an answer
 * POSTed from another browser (login CSRF) finds no request. A request is used once: it is removed before the
 * answer is checked, whatever the outcome. Expired requests are deleted as new ones are saved.
 */
public class SamlRequestStore implements Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> {

    private static final Logger log = LoggerFactory.getLogger(SamlRequestStore.class);

    static final Duration LIFETIME = Duration.ofMinutes(10);
    static final String COOKIE_PREFIX = "ENS_SAML_";
    static final String ACS_PATH = "/login/saml2/sso/";
    /** Set on the request when the request it answers came from a session that acknowledged the consent banner. */
    public static final String CONSENTED = SamlRequestStore.class.getName() + ".consented";
    private static final Pattern RELAY_STATE = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final SsoSamlRequestRepository requests;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SamlRequestStore(SsoSamlRequestRepository requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
        SsoSamlRequest row = find(request);
        return row == null ? null : deserialize(row.getRequest());
    }

    @Override
    public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authenticationRequest,
                                          HttpServletRequest request, HttpServletResponse response) {
        if (authenticationRequest == null) {
            return;
        }
        String relayState = authenticationRequest.getRelayState();
        if (relayState == null || !RELAY_STATE.matcher(cookieSafe(relayState)).matches()) {
            throw new IllegalStateException("A SAML request needs a random RelayState");
        }
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String browser = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);

        Instant now = clock.instant();
        requests.deleteExpired(now);
        SsoSamlRequest row = new SsoSamlRequest();
        row.setRelayState(relayState);
        row.setBrowserHash(sha256(browser));
        row.setRequest(serialize(authenticationRequest));
        HttpSession session = request.getSession(false);
        row.setConsented(session != null && session.getAttribute(ConsentBanner.ACKNOWLEDGED) != null);
        row.setExpiresAt(now.plus(LIFETIME));
        requests.save(row);

        boolean secure = request.isSecure();
        if (!secure) {
            log.warn("SAML sign-in started over plain HTTP: browsers send the SameSite=None cookie that ties the"
                    + " answer to this browser only over HTTPS. Serve ReadyRoom over HTTPS (or have the proxy send"
                    + " X-Forwarded-Proto) for SAML.");
        }
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_PREFIX + cookieSafe(relayState), browser)
                .path(request.getContextPath() + ACS_PATH)
                .httpOnly(true)
                .secure(secure)
                .sameSite(secure ? "None" : "Lax")
                .maxAge(LIFETIME)
                .build().toString());
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request,
                                                                          HttpServletResponse response) {
        SsoSamlRequest row = find(request);
        if (row == null || requests.deleteByRelayState(row.getRelayState()) != 1) {
            return null; // used already, by this or another replica
        }
        if (row.isConsented()) {
            request.setAttribute(CONSENTED, Boolean.TRUE);
        }
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_PREFIX + cookieSafe(row.getRelayState()), "")
                .path(request.getContextPath() + ACS_PATH).httpOnly(true).secure(request.isSecure())
                .maxAge(0).build().toString());
        return deserialize(row.getRequest());
    }

    /** The unexpired request the RelayState names, if this browser holds its cookie. */
    private SsoSamlRequest find(HttpServletRequest request) {
        String relayState = request.getParameter(Saml2ParameterNames.RELAY_STATE);
        if (relayState == null || !RELAY_STATE.matcher(cookieSafe(relayState)).matches()) {
            return null;
        }
        SsoSamlRequest row = requests.findById(relayState).orElse(null);
        if (row == null || !row.getExpiresAt().isAfter(clock.instant())) {
            return null;
        }
        String browser = cookie(request, COOKIE_PREFIX + cookieSafe(relayState));
        if (browser == null || !MessageDigest.isEqual(sha256(browser).getBytes(StandardCharsets.US_ASCII),
                row.getBrowserHash().getBytes(StandardCharsets.US_ASCII))) {
            log.info("A SAML answer for RelayState {} came from a browser that did not start that sign-in", relayState);
            return null;
        }
        return row;
    }

    private static String cookieSafe(String relayState) {
        return relayState.replace("-", "");
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] serialize(AbstractSaml2AuthenticationRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(request);
        } catch (IOException e) {
            throw new IllegalStateException("The SAML request could not be stored", e);
        }
        return bytes.toByteArray();
    }

    /** Only Spring Security's request classes (and the JDK types they hold) are read back. */
    private static AbstractSaml2AuthenticationRequest deserialize(byte[] stored) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(stored))) {
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "org.springframework.security.saml2.provider.service.authentication.*;java.lang.*;java.util.*;"
                            + "maxdepth=10;maxbytes=65536;!*"));
            return in.readObject() instanceof AbstractSaml2AuthenticationRequest request ? request : null;
        } catch (IOException | ClassNotFoundException e) {
            log.warn("A stored SAML request could not be read back: {}", e.toString());
            return null;
        }
    }
}
