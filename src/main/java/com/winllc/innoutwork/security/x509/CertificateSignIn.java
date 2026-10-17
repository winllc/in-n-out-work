package com.winllc.innoutwork.security.x509;

import com.winllc.innoutwork.audit.SecurityAudit;
import com.winllc.innoutwork.config.EnsProperties;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.security.AppUserDetailsService;
import com.winllc.innoutwork.service.LdapService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.security.auth.x500.X500Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Signs a client certificate in, for every chain that accepts certificates (SecurityConfig, the Windows client's
 * check-in chain, demo mode): the issuer must be allowed (ENS_X509_ALLOWED_ISSUERS), the certificate not revoked
 * (ENS_X509_REVOCATION), and it must map to exactly one directory user - by ENS_X509_USER_FILTER, or, when that is
 * blank, by its subject DN being the user's DN, as before. The user then gets the same roles as any other sign-in
 * ({@link AppUserDetailsService}). Each refusal is audited as a failed {@code auth.login} with its reason.
 */
public class CertificateSignIn implements AuthenticationUserDetailsService<PreAuthenticatedAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(CertificateSignIn.class);

    static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Set<String> PLACEHOLDERS = Set.of("upn", "edipi", "email", "uuid", "subject", "cn");

    private final AppUserDetailsService users;
    private final LdapService ldap;
    private final RevocationChecker revocation;
    private final TrustMaterial trust;
    private final EnsProperties.X509 settings;
    private final Set<X500Principal> allowedIssuers;
    private final ObjectProvider<SecurityAudit> audit;

    public CertificateSignIn(AppUserDetailsService users, LdapService ldap, RevocationChecker revocation,
                             TrustMaterial trust, EnsProperties.X509 settings, ObjectProvider<SecurityAudit> audit) {
        this.users = users;
        this.ldap = ldap;
        this.revocation = revocation;
        this.trust = trust;
        this.settings = settings;
        this.allowedIssuers = allowedIssuers(settings.getAllowedIssuers());
        this.audit = audit;
        String filter = settings.getUserFilter();
        if (filter != null && !filter.isBlank()) {
            Matcher m = PLACEHOLDER.matcher(filter);
            while (m.find()) {
                if (!PLACEHOLDERS.contains(m.group(1))) {
                    throw new IllegalStateException("ENS_X509_USER_FILTER has an unknown placeholder {" + m.group(1)
                            + "}; use " + PLACEHOLDERS.stream().sorted().map(p -> "{" + p + "}").toList());
                }
            }
        }
    }

    /** The issuer DNs, separated by ";" or new lines (DNs contain commas). Empty: any issuer the trust store trusts. */
    static Set<X500Principal> allowedIssuers(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        try {
            return Arrays.stream(configured.split("[;\\n]")).map(String::strip).filter(s -> !s.isEmpty())
                    .map(X500Principal::new).collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ENS_X509_ALLOWED_ISSUERS holds a value that is not a DN: " + e.getMessage(), e);
        }
    }

    @Override
    public UserDetails loadUserDetails(PreAuthenticatedAuthenticationToken token) {
        if (!(token.getCredentials() instanceof X509Certificate leaf)) {
            throw new UsernameNotFoundException("No client certificate");
        }
        HttpServletRequest request = currentRequest();
        // The chain the client sent with it, for its intermediates; only if it is this certificate's (in header
        // mode the forwarded certificate is not on the original request).
        X509Certificate[] presented = request != null && request.getAttribute(CERTIFICATE_ATTRIBUTE)
                instanceof X509Certificate[] chain && chain.length > 0 && chain[0].equals(leaf)
                ? chain : new X509Certificate[]{leaf};
        CertificateIdentity identity = CertificateIdentity.of(leaf);

        if (!allowedIssuers.isEmpty() && !allowedIssuers.contains(leaf.getIssuerX500Principal())) {
            throw refused(request, identity, "issuer not allowed");
        }
        if (revocation.enabled()) {
            X509Certificate issuer = trust.issuerOf(leaf, presented);
            RevocationChecker.Result result = issuer == null
                    ? new RevocationChecker.Result(RevocationChecker.Status.UNKNOWN, "the issuer's certificate is not known")
                    : revocation.check(leaf, issuer);
            if (result.status() == RevocationChecker.Status.REVOKED) {
                throw refused(request, identity, "certificate revoked");
            }
            if (result.status() == RevocationChecker.Status.UNKNOWN) {
                if (settings.getRevocationFail() == EnsProperties.X509.RevocationFail.CLOSED) {
                    log.warn("Refusing {} (serial {}): revocation status unknown ({})", identity.subject(),
                            identity.serial(), result.detail());
                    throw refused(request, identity, "revocation unknown");
                }
                log.warn("Allowing {} (serial {}) although its revocation status is unknown ({}): "
                        + "ENS_X509_REVOCATION_FAIL is open", identity.subject(), identity.serial(), result.detail());
            }
        }

        String dn = dn(token, identity, request);
        try {
            return users.loadUserByUsername(dn);
        } catch (UsernameNotFoundException e) {
            if (settings.getUserFilter() != null && !settings.getUserFilter().isBlank()) {
                throw refused(request, identity, "directory user could not be loaded");
            }
            throw e; // the subject DN is no directory user: audited by the failure event, as before
        }
    }

    /** The directory DN the certificate stands for. */
    private String dn(PreAuthenticatedAuthenticationToken token, CertificateIdentity identity, HttpServletRequest request) {
        String template = settings.getUserFilter();
        if (template == null || template.isBlank()) {
            return token.getName(); // the subject DN is the user's DN
        }
        Map<String, String> values = identity.placeholders();
        StringBuilder filter = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw refused(request, identity, "certificate has no " + m.group(1));
            }
            m.appendReplacement(filter, Matcher.quoteReplacement(LdapService.escapeLdapFilter(value)));
        }
        m.appendTail(filter);
        LdapUser user = ldap.lookupUniqueUserByFilter(filter.toString())
                .orElseThrow(() -> refused(request, identity, "no single directory user matches"));
        log.debug("Certificate {} maps to {} by {}", identity.subject(), user.getDn(), filter);
        return user.getDn();
    }

    private CertificateRefusedException refused(HttpServletRequest request, CertificateIdentity identity, String reason) {
        log.info("Certificate sign-in refused for {} (issuer {}, serial {}): {}", identity.subject(), identity.issuer(),
                identity.serial(), reason);
        SecurityAudit trail = audit.getIfAvailable();
        if (trail != null) {
            trail.certificateRefused(request, identity.subject(), identity.issuer(), identity.serial(), reason);
        }
        return new CertificateRefusedException(reason);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet
                ? servlet.getRequest() : null;
    }

    /** For startup logging. */
    List<String> describe() {
        return List.of("mapping " + (settings.getUserFilter() == null || settings.getUserFilter().isBlank()
                        ? "subject DN = directory DN" : settings.getUserFilter()),
                "issuers " + (allowedIssuers.isEmpty() ? "any trusted" : allowedIssuers),
                "revocation " + settings.getRevocation().name().toLowerCase().replace('_', '-'));
    }
}
