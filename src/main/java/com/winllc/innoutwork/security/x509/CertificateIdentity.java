package com.winllc.innoutwork.security.x509;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a client certificate says about who holds it, as the values ENS_X509_USER_FILTER's placeholders take:
 * <ul>
 *   <li>{@code {subject}} the subject DN (RFC 2253), {@code {cn}} its most specific common name;</li>
 *   <li>{@code {upn}} the subject alternative name otherName UserPrincipalName (1.3.6.1.4.1.311.20.2.3);</li>
 *   <li>{@code {edipi}} the DoD EDIPI: the digits before {@code @} of a UPN {@code 1234567890@mil}, or else the
 *       trailing {@code .NNNNNNNNNN} of a CN {@code LAST.FIRST.MI.1234567890};</li>
 *   <li>{@code {email}} the rfc822Name subject alternative name, or else the subject's emailAddress;</li>
 *   <li>{@code {uuid}} the UUID of a {@code urn:uuid:} URI subject alternative name (PIV FASC-N UUID).</li>
 * </ul>
 */
public record CertificateIdentity(String subject, String issuer, String serial, String cn, String upn, String edipi,
                                  String email, String uuid) {

    static final String OID_UPN = "1.3.6.1.4.1.311.20.2.3";
    static final String OID_EMAIL_ADDRESS = "1.2.840.113549.1.9.1";

    private static final Pattern EDIPI_UPN = Pattern.compile("^(\\d{10})@mil$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EDIPI_CN = Pattern.compile("\\.(\\d{10})$");
    private static final Pattern UUID_URN = Pattern.compile("^urn:uuid:([0-9a-fA-F-]{36})$");

    public static CertificateIdentity of(X509Certificate cert) {
        String subject = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
        String issuer = cert.getIssuerX500Principal().getName(X500Principal.RFC2253);
        String cn = null;
        String subjectEmail = null;
        try {
            List<Rdn> rdns = new LdapName(subject).getRdns(); // least specific first
            for (int i = rdns.size() - 1; i >= 0; i--) {
                Rdn rdn = rdns.get(i);
                String type = rdn.getType().toLowerCase(Locale.ROOT);
                if (cn == null && type.equals("cn") && rdn.getValue() instanceof String value) {
                    cn = value;
                }
                if (subjectEmail == null && (type.equals("emailaddress") || type.equals(OID_EMAIL_ADDRESS)
                        || type.equals("oid." + OID_EMAIL_ADDRESS))) {
                    subjectEmail = rdn.getValue() instanceof String value ? value : emailFromBer(rdn.getValue());
                }
            }
        } catch (InvalidNameException e) {
            // Leave them empty; a template needing them refuses the certificate.
        }

        String upn = null;
        String email = null;
        String uuid = null;
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    int type = (Integer) name.get(0);
                    if (type == 0 && upn == null && name.get(1) instanceof byte[] der) {
                        upn = otherName(der, OID_UPN);
                    } else if (type == 1 && email == null && name.get(1) instanceof String rfc822) {
                        email = rfc822;
                    } else if (type == 6 && uuid == null && name.get(1) instanceof String uri) {
                        Matcher m = UUID_URN.matcher(uri.strip());
                        if (m.matches()) {
                            uuid = m.group(1);
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // A malformed extension: the subject's values still apply.
        }
        if (email == null) {
            email = subjectEmail;
        }

        String edipi = null;
        if (upn != null) {
            Matcher m = EDIPI_UPN.matcher(upn);
            if (m.matches()) {
                edipi = m.group(1);
            }
        }
        if (edipi == null && cn != null) {
            Matcher m = EDIPI_CN.matcher(cn);
            if (m.find()) {
                edipi = m.group(1);
            }
        }
        return new CertificateIdentity(subject, issuer, cert.getSerialNumber().toString(16), cn, upn, edipi, email,
                uuid);
    }

    /** The placeholder values present, by name. */
    public Map<String, String> placeholders() {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "subject", subject);
        put(values, "cn", cn);
        put(values, "upn", upn);
        put(values, "edipi", edipi);
        put(values, "email", email);
        put(values, "uuid", uuid);
        return values;
    }

    private static void put(Map<String, String> values, String name, String value) {
        if (value != null && !value.isBlank()) {
            values.put(name, value);
        }
    }

    /**
     * A UTF8String value of type {@code wantedOid} from a DER otherName, which the JDK hands back either as the
     * OtherName SEQUENCE or as the [0]-tagged GeneralName holding the same two parts.
     */
    static String otherName(byte[] der, String wantedOid) {
        Der.Tlv outer = Der.single(der);
        if (outer == null) {
            return null;
        }
        List<Der.Tlv> parts = outer.children();
        if (parts.size() < 2 || parts.get(0).tag() != Der.OID || !wantedOid.equals(Der.oid(parts.get(0).value()))) {
            return null;
        }
        Der.Tlv value = parts.get(1).isContext(0) ? Der.single(parts.get(1).value()) : parts.get(1);
        return value == null ? null : value.text();
    }

    /** RFC 2253 shows an unknown attribute type's value as #hex BER, which LdapName returns as bytes. */
    private static String emailFromBer(Object value) {
        if (value instanceof byte[] ber) {
            Der.Tlv tlv = Der.single(ber);
            return tlv == null ? null : tlv.text();
        }
        return null;
    }
}
